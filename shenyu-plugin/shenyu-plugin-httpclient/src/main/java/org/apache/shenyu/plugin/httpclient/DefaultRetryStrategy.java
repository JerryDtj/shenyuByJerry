package org.apache.shenyu.plugin.httpclient;

import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.enums.RetryEnum;
import org.apache.shenyu.common.exception.ShenyuException;
import org.apache.shenyu.loadbalancer.cache.UpstreamCacheManager;
import org.apache.shenyu.loadbalancer.entity.Upstream;
import org.apache.shenyu.loadbalancer.factory.LoadBalancerFactory;
import org.apache.shenyu.plugin.api.utils.RequestUrlUtils;
import org.apache.shenyu.plugin.httpclient.exception.ShenyuTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;


/**
 * Default Retry Policy Class
 * Keep the original default request retry test without any changes.
 * @param <R> Request Response Type
 * @author Jerry
 * @Date 2025/3/23 08:36
 */
public class DefaultRetryStrategy<R> implements RetryStrategy<R> {
    private final AbstractHttpClientPlugin<R> httpClientPlugin;

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRetryStrategy.class);

    public DefaultRetryStrategy(AbstractHttpClientPlugin<R> httpClientPlugin) {
        this.httpClientPlugin = httpClientPlugin;
    }

    @Override
    public Mono<R> execute(Mono<R> clientResponse, ServerWebExchange exchange, Duration duration, int retryTimes) {
        final String retryStrategy = (String) Optional.ofNullable(exchange.getAttribute(Constants.RETRY_STRATEGY)).orElseGet(() -> "current");
        if (RetryEnum.CURRENT.getName().equals(retryStrategy)) {
            //old version of DividePlugin and SpringCloudPlugin will run on this
            RetryBackoffSpec retryBackoffSpec = Retry.backoff(retryTimes, Duration.ofMillis(20L))
                    .maxBackoff(Duration.ofSeconds(20L))
                    .transientErrors(true)
                    .jitter(0.5d)
                    .filter(t -> t instanceof java.util.concurrent.TimeoutException || t instanceof io.netty.channel.ConnectTimeoutException
                            || t instanceof io.netty.handler.timeout.ReadTimeoutException || t instanceof IllegalStateException)
                    .onRetryExhaustedThrow((retryBackoffSpecErr, retrySignal) -> {
                        throw new ShenyuTimeoutException("Request timeout, the maximum number of retry times has been exceeded");
                    });
            return clientResponse.retryWhen(retryBackoffSpec)
                    .onErrorMap(ShenyuTimeoutException.class, th -> new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, th.getMessage(), th))
                    .onErrorMap(java.util.concurrent.TimeoutException.class, th -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, th.getMessage(), th));
        }
        final Set<URI> exclude = new HashSet<>(Collections.singletonList(Objects.requireNonNull(exchange.getAttribute(Constants.HTTP_URI))));
        return resend(clientResponse, exchange, duration, exclude, retryTimes)
                .onErrorMap(ShenyuException.class, th -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "CANNOT_FIND_HEALTHY_UPSTREAM_URL_AFTER_FAILOVER", th))
                .onErrorMap(java.util.concurrent.TimeoutException.class, th -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, th.getMessage(), th));
    }

    private Mono<R> resend(final Mono<R> clientResponse,
                           final ServerWebExchange exchange,
                           final Duration duration,
                           final Set<URI> exclude,
                           final int retryTimes) {
        Mono<R> result = clientResponse;
        for (int i = 0; i < retryTimes; i++) {
            result = resend(result, exchange, duration, exclude);
        }
        return result;
    }

    private Mono<R> resend(final Mono<R> response,
                           final ServerWebExchange exchange,
                           final Duration duration,
                           final Set<URI> exclude) {
        // does it necessary to add backoff interval time ?
        return response.onErrorResume(th -> {
            final String selectorId = exchange.getAttribute(Constants.DIVIDE_SELECTOR_ID);
            final String loadBalance = exchange.getAttribute(Constants.LOAD_BALANCE);
            //always query the latest available list
            final List<Upstream> upstreamList = UpstreamCacheManager.getInstance().findUpstreamListBySelectorId(selectorId)
                    .stream().filter(data -> {
                        final String trimUri = data.getUrl().trim();
                        for (URI needToExclude : exclude) {
                            if ((needToExclude.getHost() + ":" + needToExclude.getPort()).equals(trimUri)) {
                                return false;
                            }
                        }
                        return true;
                    }).collect(Collectors.toList());
            if (upstreamList.isEmpty()) {
                // no need to retry anymore
                return Mono.error(new ShenyuException("CANNOT_FIND_HEALTHY_UPSTREAM_URL_AFTER_FAILOVER"));
            }
            final String ip = Objects.requireNonNull(exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress();
            final Upstream upstream = LoadBalancerFactory.selector(upstreamList, loadBalance, ip);
            if (upstream == null) {
                // no need to retry anymore
                return Mono.error(new ShenyuException("CANNOT_FIND_HEALTHY_UPSTREAM_URL_AFTER_FAILOVER"));
            }
            final URI newUri = RequestUrlUtils.buildRequestUri(exchange, upstream.buildDomain());
            // in order not to affect the next retry call, newUri needs to be excluded
            exclude.add(newUri);
            return httpClientPlugin.doRequest(exchange, exchange.getRequest().getMethod().name(), newUri, exchange.getRequest().getBody())
                    .timeout(duration, Mono.error(() -> new TimeoutException("Response took longer than timeout: " + duration)))
                    .doOnError(e -> LOG.error(e.getMessage(), e));
        });
    }
}
