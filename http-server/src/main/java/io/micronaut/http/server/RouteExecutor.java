/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server;

import io.micronaut.context.BeanContext;
import io.micronaut.context.exceptions.BeanCreationException;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.buffer.ReferenceCounted;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpHeaders;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.bind.binders.ContinuationArgumentBinder;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.http.server.binding.RequestArgumentSatisfier;
import io.micronaut.http.server.exceptions.response.ErrorContext;
import io.micronaut.http.server.exceptions.response.ErrorResponseProcessor;
import io.micronaut.inject.BeanType;
import io.micronaut.inject.MethodReference;
import io.micronaut.scheduling.executor.ExecutorSelector;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.RouteMatch;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.exceptions.DuplicateRouteException;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CorePublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.micronaut.core.util.KotlinUtils.isKotlinCoroutineSuspended;
import static io.micronaut.http.HttpAttributes.AVAILABLE_HTTP_METHODS;
import static io.micronaut.inject.beans.KotlinExecutableMethodUtils.isKotlinFunctionReturnTypeUnit;

/**
 * A class responsible for executing routes.
 *
 * @author James Kleeh
 * @since 3.0.0
 */
@Singleton
public final class RouteExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RouteExecutor.class);
    /**
     * Also present in netty RoutingInBoundHandler.
     */
    private static final Pattern IGNORABLE_ERROR_MESSAGE = Pattern.compile(
        "^.*(?:connection (?:reset|closed|abort|broken)|broken pipe).*$", Pattern.CASE_INSENSITIVE);

    final Router router;
    final BeanContext beanContext;
    final RequestArgumentSatisfier requestArgumentSatisfier;
    final HttpServerConfiguration serverConfiguration;
    final ErrorResponseProcessor<?> errorResponseProcessor;
    private final ExecutorSelector executorSelector;
    private final Optional<CoroutineHelper> coroutineHelper;

    /**
     * Default constructor.
     *
     * @param router                   The router
     * @param beanContext              The bean context
     * @param requestArgumentSatisfier The request argument satisfier
     * @param serverConfiguration      The server configuration
     * @param errorResponseProcessor   The error response processor
     * @param executorSelector         The executor selector
     */
    public RouteExecutor(Router router,
                         BeanContext beanContext,
                         RequestArgumentSatisfier requestArgumentSatisfier,
                         HttpServerConfiguration serverConfiguration,
                         ErrorResponseProcessor<?> errorResponseProcessor,
                         ExecutorSelector executorSelector) {
        this.router = router;
        this.beanContext = beanContext;
        this.requestArgumentSatisfier = requestArgumentSatisfier;
        this.serverConfiguration = serverConfiguration;
        this.errorResponseProcessor = errorResponseProcessor;
        this.executorSelector = executorSelector;
        this.coroutineHelper = beanContext.findBean(CoroutineHelper.class);
    }

    /**
     * @return The router
     */
    public @NonNull Router getRouter() {
        return router;
    }

    /**
     * @return The request argument satisfier
     */
    @Internal
    public @NonNull RequestArgumentSatisfier getRequestArgumentSatisfier() {
        return requestArgumentSatisfier;
    }

    /**
     * @return The error response processor
     */
    public @NonNull ErrorResponseProcessor<?> getErrorResponseProcessor() {
        return errorResponseProcessor;
    }

    /**
     * @return The executor selector
     */
    public @NonNull ExecutorSelector getExecutorSelector() {
        return executorSelector;
    }

    /**
     * @return The kotlin coroutine helper
     */
    public Optional<CoroutineHelper> getCoroutineHelper() {
        return coroutineHelper;
    }

    @Nullable
    UriRouteMatch<Object, Object> findRouteMatch(HttpRequest<?> httpRequest) {
        UriRouteMatch<Object, Object> routeMatch = null;

        List<UriRouteMatch<Object, Object>> uriRoutes = router.findAllClosest(httpRequest);
        if (uriRoutes.size() > 1) {
            throw new DuplicateRouteException(httpRequest.getPath(), uriRoutes);
        } else if (uriRoutes.size() == 1) {
            routeMatch = uriRoutes.get(0);
        }

        if (routeMatch == null && httpRequest.getMethod().equals(HttpMethod.OPTIONS)) {
            List<UriRouteMatch<Object, Object>> anyUriRoutes = router.findAny(httpRequest.getPath(), httpRequest).toList();
            if (!anyUriRoutes.isEmpty()) {
                setRouteAttributes(httpRequest, anyUriRoutes.get(0));
                httpRequest.setAttribute(AVAILABLE_HTTP_METHODS, anyUriRoutes.stream().map(UriRouteMatch::getHttpMethod).toList());
            }
        }
        return routeMatch;
    }

    static void setRouteAttributes(HttpRequest<?> request, UriRouteMatch<Object, Object> route) {
        request.setAttribute(HttpAttributes.ROUTE, route.getRoute());
        request.setAttribute(HttpAttributes.ROUTE_MATCH, route);
        request.setAttribute(HttpAttributes.ROUTE_INFO, route);
        request.setAttribute(HttpAttributes.URI_TEMPLATE, route.getRoute().getUriMatchTemplate().toString());
    }

    /**
     * Creates a default error response. Should be used when a response could not be retrieved
     * from any other method.
     *
     * @param httpRequest The request that case the exception
     * @param cause       The exception that occurred
     * @return A response to represent the exception
     */
    public MutableHttpResponse<?> createDefaultErrorResponse(HttpRequest<?> httpRequest,
                                                             Throwable cause) {
        logException(cause);
        final MutableHttpResponse<Object> response = HttpResponse.serverError();
        response.setAttribute(HttpAttributes.EXCEPTION, cause);
        response.setAttribute(HttpAttributes.ROUTE_INFO, new RouteInfo<MutableHttpResponse>() {
            @Override
            public ReturnType<MutableHttpResponse> getReturnType() {
                return ReturnType.of(MutableHttpResponse.class, Argument.OBJECT_ARGUMENT);
            }

            @Override
            public Class<?> getDeclaringType() {
                return Object.class;
            }

            @Override
            public boolean isErrorRoute() {
                return true;
            }
        });
        MutableHttpResponse<?> mutableHttpResponse = errorResponseProcessor.processResponse(
            ErrorContext.builder(httpRequest)
                .cause(cause)
                .errorMessage("Internal Server Error: " + cause.getMessage())
                .build(), response);
        applyConfiguredHeaders(mutableHttpResponse.getHeaders());
        if (!mutableHttpResponse.getContentType().isPresent() && httpRequest.getMethod() != HttpMethod.HEAD) {
            return mutableHttpResponse.contentType(MediaType.APPLICATION_JSON_TYPE);
        }
        return mutableHttpResponse;
    }

    /**
     * @param request    The request
     * @param finalRoute The route
     * @return The default content type declared on the route
     */
    public MediaType resolveDefaultResponseContentType(HttpRequest<?> request, RouteInfo<?> finalRoute) {
        final List<MediaType> producesList = finalRoute.getProduces();
        if (request != null) {
            final Iterator<MediaType> i = request.accept().iterator();
            if (i.hasNext()) {
                final MediaType mt = i.next();
                if (producesList.contains(mt)) {
                    return mt;
                }
            }
        }

        MediaType defaultResponseMediaType;
        final Iterator<MediaType> produces = producesList.iterator();
        if (produces.hasNext()) {
            defaultResponseMediaType = produces.next();
        } else {
            defaultResponseMediaType = MediaType.APPLICATION_JSON_TYPE;
        }
        return defaultResponseMediaType;
    }

    private MutableHttpResponse<?> newNotFoundError(HttpRequest<?> request) {
        MutableHttpResponse<?> response = errorResponseProcessor.processResponse(
            ErrorContext.builder(request)
                .errorMessage("Page Not Found")
                .build(), HttpResponse.notFound());
        if (response.getContentType().isEmpty() && request.getMethod() != HttpMethod.HEAD) {
            return response.contentType(MediaType.APPLICATION_JSON_TYPE);
        }
        return response;
    }

    private Mono<MutableHttpResponse<?>> createNotFoundErrorResponsePublisher(HttpRequest<?> httpRequest) {
        return Mono.fromCallable(() -> newNotFoundError(httpRequest));
    }

    void logException(Throwable cause) {
        //handling connection reset by peer exceptions
        if (isIgnorable(cause)) {
            logIgnoredException(cause);
        } else {
            if (LOG.isErrorEnabled()) {
                LOG.error("Unexpected error occurred: " + cause.getMessage(), cause);
            }
        }
    }

    static boolean isIgnorable(Throwable cause) {
        String message = cause.getMessage();
        return cause instanceof IOException && message != null && IGNORABLE_ERROR_MESSAGE.matcher(message).matches();
    }

    static void logIgnoredException(Throwable cause) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Swallowed an IOException caused by client connectivity: " + cause.getMessage(), cause);
        }
    }

    RouteMatch<?> findErrorRoute(Throwable cause,
                                         Class<?> declaringType,
                                         HttpRequest<?> httpRequest) {
        RouteMatch<?> errorRoute = null;
        if (cause instanceof BeanCreationException && declaringType != null) {
            // If the controller could not be instantiated, don't look for a local error route
            Optional<Class<?>> rootBeanType = ((BeanCreationException) cause).getRootBeanType().map(BeanType::getBeanType);
            if (rootBeanType.isPresent() && declaringType == rootBeanType.get()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Failed to instantiate [{}]. Skipping lookup of a local error route", declaringType.getName());
                }
                declaringType = null;
            }
        }

        // First try to find an error route by the exception
        if (declaringType != null) {
            // handle error with a method that is non global with exception
            errorRoute = router.findErrorRoute(declaringType, cause, httpRequest).orElse(null);
        }
        if (errorRoute == null) {
            // handle error with a method that is global with exception
            errorRoute = router.findErrorRoute(cause, httpRequest).orElse(null);
        }

        if (errorRoute == null) {
            // Second try is by status route if the status is known
            HttpStatus errorStatus = null;
            if (cause instanceof UnsatisfiedRouteException || cause instanceof CodecException) {
                // when arguments do not match, then there is UnsatisfiedRouteException, we can handle this with a routed bad request
                // or when incoming request body is not in the expected format
                errorStatus = HttpStatus.BAD_REQUEST;
            } else if (cause instanceof HttpStatusException) {
                errorStatus = ((HttpStatusException) cause).getStatus();
            }

            if (errorStatus != null) {
                if (declaringType != null) {
                    // handle error with a method that is non global with bad request
                    errorRoute = router.findStatusRoute(declaringType, errorStatus, httpRequest).orElse(null);
                }
                if (errorRoute == null) {
                    // handle error with a method that is global with bad request
                    errorRoute = router.findStatusRoute(errorStatus, httpRequest).orElse(null);
                }
            }
        }

        if (errorRoute != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Found matching exception handler for exception [{}]: {}", cause.getMessage(), errorRoute);
            }
            errorRoute = requestArgumentSatisfier.fulfillArgumentRequirements(errorRoute, httpRequest, false);
        }

        return errorRoute;
    }

    RouteMatch<Object> findStatusRoute(HttpRequest<?> incomingRequest, HttpStatus status, RouteInfo<?> finalRoute) {
        Class<?> declaringType = finalRoute.getDeclaringType();
        // handle re-mapping of errors
        RouteMatch<Object> statusRoute = null;
        // if declaringType is not null, this means its a locally marked method handler
        if (declaringType != null) {
            statusRoute = router.findStatusRoute(declaringType, status, incomingRequest)
                .orElseGet(() -> router.findStatusRoute(status, incomingRequest).orElse(null));
        }
        return statusRoute;
    }

    ExecutorService findExecutor(RouteInfo<?> routeMatch) {
        // Select the most appropriate Executor
        ExecutorService executor;
        if (routeMatch instanceof MethodReference) {
            executor = executorSelector.select((MethodReference<?, ?>) routeMatch, serverConfiguration.getThreadSelection()).orElse(null);
        } else {
            executor = null;
        }
        return executor;
    }

    private <T> Flux<T> applyExecutorToPublisher(Publisher<T> publisher, @Nullable ExecutorService executor) {
        if (executor != null) {
            final Scheduler scheduler = Schedulers.fromExecutorService(executor);
            return Flux.from(publisher)
                .subscribeOn(scheduler)
                .publishOn(scheduler);
        } else {
            return Flux.from(publisher);
        }
    }

    private boolean isSingle(RouteInfo<?> finalRoute, Class<?> bodyClass) {
        return finalRoute.isSpecifiedSingle() || (finalRoute.isSingleResult() &&
            (finalRoute.isAsync() || finalRoute.isSuspended() || Publishers.isSingle(bodyClass)));
    }

    private MutableHttpResponse<?> toMutableResponse(HttpResponse<?> message) {
        MutableHttpResponse<?> mutableHttpResponse;
        if (message instanceof MutableHttpResponse) {
            mutableHttpResponse = (MutableHttpResponse<?>) message;
        } else {
            mutableHttpResponse = HttpResponse.status(message.code(), message.reason());
            mutableHttpResponse.body(message.body());
            message.getHeaders().forEach((name, value) -> {
                for (String val : value) {
                    mutableHttpResponse.header(name, val);
                }
            });
            mutableHttpResponse.getAttributes().putAll(message.getAttributes());
        }
        return mutableHttpResponse;
    }

    private ExecutionFlow<MutableHttpResponse<?>> fromImperativeExecute(HttpRequest<?> request, RouteInfo<?> routeInfo, HttpStatus defaultHttpStatus, Object body) {
        if (body instanceof HttpResponse) {
            MutableHttpResponse<?> outgoingResponse = toMutableResponse((HttpResponse<?>) body);
            final Argument<?> bodyArgument = routeInfo.getReturnType().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            if (bodyArgument.isAsyncOrReactive()) {
                return fromPublisher(
                    processPublisherBody(request, outgoingResponse, routeInfo)
                );
            }
            return ExecutionFlow.just(outgoingResponse);
        }
        return ExecutionFlow.just(forStatus(routeInfo, defaultHttpStatus).body(body));
    }

    ExecutionFlow<MutableHttpResponse<?>> callRoute(ContextView contextFromFilter, RouteMatch<?> routeMatch, HttpRequest<?> request) {
        ExecutorService executorService = findExecutor(routeMatch);
        Supplier<ExecutionFlow<MutableHttpResponse<?>>> flowSupplier = () -> executeRouteAndConvertBody(routeMatch, request);
        ExecutionFlow<MutableHttpResponse<?>> executeMethodResponseFlow;
        if (executorService != null) {
            if (routeMatch.isSuspended()) {
                executeMethodResponseFlow = ReactiveExecutionFlow.fromPublisher(Mono.deferContextual(contextView -> {
                        coroutineHelper.ifPresent(helper -> helper.setupCoroutineContext(request, contextView));
                        return Mono.from(
                            ReactiveExecutionFlow.fromFlow(flowSupplier.get()).toPublisher()
                        );
                    }).contextWrite(contextFromFilter))
                    .putInContext(ServerRequestContext.KEY, request);
            } else if (routeMatch.isReactive()) {
                executeMethodResponseFlow = ReactiveExecutionFlow.async(executorService, flowSupplier)
                    .putInContext(ServerRequestContext.KEY, request);
            } else {
                executeMethodResponseFlow = ExecutionFlow.async(executorService, flowSupplier);
            }
        } else {
            if (routeMatch.isSuspended()) {
                executeMethodResponseFlow = ReactiveExecutionFlow.fromPublisher(Mono.deferContextual(contextView -> {
                        coroutineHelper.ifPresent(helper -> helper.setupCoroutineContext(request, contextView));
                        return Mono.from(
                            ReactiveExecutionFlow.fromFlow(flowSupplier.get()).toPublisher()
                        );
                    }).contextWrite(contextFromFilter))
                    .putInContext(ServerRequestContext.KEY, request);
            } else if (routeMatch.isReactive()) {
                executeMethodResponseFlow = ReactiveExecutionFlow.fromFlow(flowSupplier.get())
                    .putInContext(ServerRequestContext.KEY, request);
            } else {
                executeMethodResponseFlow = flowSupplier.get();
            }
        }
        return executeMethodResponseFlow;
    }

    private ExecutionFlow<MutableHttpResponse<?>> executeRouteAndConvertBody(RouteMatch<?> routeMatch, HttpRequest<?> httpRequest) {
        try {
            final RouteMatch<?> finalRoute;
            // ensure the route requirements are completely satisfied
            if (!routeMatch.isExecutable()) {
                finalRoute = requestArgumentSatisfier
                    .fulfillArgumentRequirements(routeMatch, httpRequest, true);
            } else {
                finalRoute = routeMatch;
            }
            Object body = ServerRequestContext.with(httpRequest, (Supplier<Object>) finalRoute::execute);
            if (body instanceof Optional) {
                body = ((Optional<?>) body).orElse(null);
            }
            return createResponseForBody(httpRequest, body, finalRoute);
        } catch (Throwable e) {
            return ExecutionFlow.error(e);
        }
    }

    ExecutionFlow<MutableHttpResponse<?>> createResponseForBody(HttpRequest<?> request,
                                                                        Object body,
                                                                        RouteInfo<?> routeInfo) {
        ExecutionFlow<MutableHttpResponse<?>> outgoingResponse;
        if (body == null) {
            if (routeInfo.isVoid()) {
                MutableHttpResponse<Object> data = forStatus(routeInfo);
                if (HttpMethod.permitsRequestBody(request.getMethod())) {
                    data.header(HttpHeaders.CONTENT_LENGTH, "0");
                }
                outgoingResponse = ExecutionFlow.just(data);
            } else {
                outgoingResponse = ExecutionFlow.just(newNotFoundError(request));
            }
        } else {
            HttpStatus defaultHttpStatus = routeInfo.isErrorRoute() ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
            boolean isReactive = routeInfo.isAsyncOrReactive() || Publishers.isConvertibleToPublisher(body);
            if (isReactive) {
                outgoingResponse = ReactiveExecutionFlow.fromPublisher(
                    fromReactiveExecute(request, body, routeInfo, defaultHttpStatus)
                );
            } else if (body instanceof HttpStatus httpStatus) { // now we have the raw result, transform it as necessary
                outgoingResponse = ExecutionFlow.just(HttpResponse.status(httpStatus));
            } else {
                if (routeInfo.isSuspended()) {
                    outgoingResponse = fromKotlinCoroutineExecute(request, body, routeInfo, defaultHttpStatus);
                } else {
                    outgoingResponse = fromImperativeExecute(request, routeInfo, defaultHttpStatus, body);
                }
            }
        }
        outgoingResponse = outgoingResponse.map(response -> {
            // for head request we never emit the body
            if (request != null && request.getMethod().equals(HttpMethod.HEAD)) {
                final Object o = response.getBody().orElse(null);
                if (o instanceof ReferenceCounted referenceCounted) {
                    referenceCounted.release();
                }
                response.body(null);
            }
            applyConfiguredHeaders(response.getHeaders());
            if (routeInfo instanceof RouteMatch) {
                response.setAttribute(HttpAttributes.ROUTE_MATCH, routeInfo);
            }
            response.setAttribute(HttpAttributes.ROUTE_INFO, routeInfo);
            return response;
        });
        return outgoingResponse;
    }

    private ExecutionFlow<MutableHttpResponse<?>> fromKotlinCoroutineExecute(HttpRequest<?> request, Object body, RouteInfo<?> routeInfo, HttpStatus defaultHttpStatus) {
        ExecutionFlow<MutableHttpResponse<?>> outgoingResponse;
        boolean isKotlinFunctionReturnTypeUnit =
            routeInfo instanceof MethodBasedRouteMatch &&
                isKotlinFunctionReturnTypeUnit(((MethodBasedRouteMatch) routeInfo).getExecutableMethod());
        final Supplier<CompletableFuture<?>> supplier = ContinuationArgumentBinder.extractContinuationCompletableFutureSupplier(request);
        if (isKotlinCoroutineSuspended(body)) {
            return ReactiveExecutionFlow.fromPublisher(
                Mono.fromCompletionStage(supplier)
                    .flatMap(obj -> {
                        MutableHttpResponse<?> response;
                        if (obj instanceof HttpResponse) {
                            response = toMutableResponse((HttpResponse<?>) obj);
                            final Argument<?> bodyArgument = routeInfo.getReturnType().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
                            if (bodyArgument.isAsyncOrReactive()) {
                                return processPublisherBody(request, response, routeInfo);
                            }
                        } else {
                            response = forStatus(routeInfo, defaultHttpStatus);
                            if (!isKotlinFunctionReturnTypeUnit) {
                                response = response.body(obj);
                            }
                        }
                        return Mono.just(response);
                    })
                    .switchIfEmpty(createNotFoundErrorResponsePublisher(request))
            );
        }
        Object suspendedBody;
        if (isKotlinFunctionReturnTypeUnit) {
            suspendedBody = Mono.empty();
        } else {
            suspendedBody = body;
        }
        outgoingResponse = fromImperativeExecute(request, routeInfo, defaultHttpStatus, suspendedBody);
        return outgoingResponse;
    }

    private CorePublisher<MutableHttpResponse<?>> fromReactiveExecute(HttpRequest<?> request, Object body, RouteInfo<?> routeInfo, HttpStatus defaultHttpStatus) {
        Class<?> bodyClass = body.getClass();
        boolean isSingle = isSingle(routeInfo, bodyClass);
        boolean isCompletable = !isSingle && routeInfo.isVoid() && Publishers.isCompletable(bodyClass);
        if (isSingle || isCompletable) {
            // full response case
            Publisher<Object> publisher = Publishers.convertPublisher(body, Publisher.class);
            Supplier<MutableHttpResponse<?>> emptyResponse = () -> {
                MutableHttpResponse<?> singleResponse;
                if (isCompletable || routeInfo.isVoid()) {
                    singleResponse = forStatus(routeInfo, HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_LENGTH, "0");
                } else {
                    singleResponse = newNotFoundError(request);
                }
                return singleResponse;
            };
            return Flux.from(publisher)
                .flatMap(o -> {
                    MutableHttpResponse<?> singleResponse;
                    if (o instanceof Optional<?> optional) {
                        if (optional.isPresent()) {
                            o = optional.get();
                        } else {
                            return Flux.just(emptyResponse.get());
                        }
                    }
                    if (o instanceof HttpResponse) {
                        singleResponse = toMutableResponse((HttpResponse<?>) o);
                        final Argument<?> bodyArgument = routeInfo.getReturnType() //Mono
                            .getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT) //HttpResponse
                            .getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT); //Mono
                        if (bodyArgument.isAsyncOrReactive()) {
                            return processPublisherBody(request, singleResponse, routeInfo);
                        }
                    } else if (o instanceof HttpStatus) {
                        singleResponse = forStatus(routeInfo, (HttpStatus) o);
                    } else {
                        singleResponse = forStatus(routeInfo, defaultHttpStatus)
                            .body(o);
                    }
                    return Flux.just(singleResponse);
                })
                .switchIfEmpty(Mono.fromSupplier(emptyResponse));
        }
        // streaming case
        Argument<?> typeArgument = routeInfo.getReturnType().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
        if (HttpResponse.class.isAssignableFrom(typeArgument.getType())) {
            // a response stream
            Publisher<HttpResponse<?>> bodyPublisher = Publishers.convertPublisher(body, Publisher.class);
            Flux<MutableHttpResponse<?>> response = Flux.from(bodyPublisher)
                .map(this::toMutableResponse);
            Argument<?> bodyArgument = typeArgument.getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
            if (bodyArgument.isAsyncOrReactive()) {
                return response.flatMap((resp) ->
                    processPublisherBody(request, resp, routeInfo));
            }
            return response;
        }
        MutableHttpResponse<?> response = forStatus(routeInfo, defaultHttpStatus).body(body);
        return processPublisherBody(request, response, routeInfo);
    }

    private Mono<MutableHttpResponse<?>> processPublisherBody(HttpRequest<?> request,
                                                              MutableHttpResponse<?> response,
                                                              RouteInfo<?> routeInfo) {
        Object body = response.body();
        if (body == null) {
            return Mono.just(response);
        }
        if (Publishers.isSingle(body.getClass())) {
            return Mono.from(Publishers.convertPublisher(body, Publisher.class)).map(b -> {
                response.body(b);
                return response;
            });
        }
        MediaType mediaType = response.getContentType().orElseGet(() -> resolveDefaultResponseContentType(request, routeInfo));

        Flux<Object> bodyPublisher = applyExecutorToPublisher(Publishers.convertPublisher(body, Publisher.class), findExecutor(routeInfo));

        return Mono.just(response
            .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
            .header(HttpHeaders.CONTENT_TYPE, mediaType)
            .body(bodyPublisher));
    }

    private void applyConfiguredHeaders(MutableHttpHeaders headers) {
        if (serverConfiguration.isDateHeader() && !headers.contains(HttpHeaders.DATE)) {
            headers.date(LocalDateTime.now());
        }
        if (!headers.contains(HttpHeaders.SERVER)) {
            serverConfiguration.getServerHeader()
                .ifPresent(header -> headers.add(HttpHeaders.SERVER, header));
        }
    }

    private MutableHttpResponse<Object> forStatus(RouteInfo<?> routeMatch) {
        return forStatus(routeMatch, HttpStatus.OK);
    }

    private MutableHttpResponse<Object> forStatus(RouteInfo<?> routeMatch, HttpStatus defaultStatus) {
        HttpStatus status = routeMatch.findStatus(defaultStatus);
        return HttpResponse.status(status);
    }

    static <K> ExecutionFlow<K> fromPublisher(Publisher<K> publisher) {
        return ReactiveExecutionFlow.fromPublisher(publisher);
    }

}
