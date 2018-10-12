package ru.misterparser.futbol24;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import ru.misterparser.common.ControlledRunnable;
import ru.misterparser.common.PoolingHttpClient;
import ru.misterparser.common.Utils;
import ru.misterparser.common.configuration.ConfigurationUtils;
import ru.misterparser.common.proxy.ProxyInfo;
import ru.misterparser.common.proxy.ProxyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@SuppressWarnings("deprecation")
abstract class Loader extends ControlledRunnable {

    private static final String ENCODING = "UTF-8";

    private volatile ProxyInfo currentProxy;
    private final List<ProxyInfo> proxyInfos = new CopyOnWriteArrayList<>();
    boolean isCache;

    private synchronized void init() throws InterruptedException {
        while (proxyInfos.size() == 0) {
            ProxyUtils.loadFromFile(proxyInfos, new ArrayList<>(), true, false);
            Thread.sleep(5000);
        }
    }

    private DefaultHttpClient getHttpClient() throws InterruptedException {
        init();
        currentProxy = proxyInfos.get(RandomUtils.nextInt(0, proxyInfos.size()));
        Thread.currentThread().setName(currentProxy.toString());
        log.debug("Выбран прокси: " + currentProxy + ", всего прокси: " + proxyInfos.size());
        DefaultHttpClient httpClient = PoolingHttpClient.getHttpClient(currentProxy, true, Configuration.NETWORK_TIMEOUT);
        httpClient.setHttpRequestRetryHandler(new DefaultHttpRequestRetryHandler(0, false));
        return httpClient;
    }

    String fetch(String url, Map<String, String> headers) throws InterruptedException {
        String page = "";
        boolean inited = false;
        while (!inited) {
            DefaultHttpClient httpClient = getHttpClient();
            try {
                log.debug("Обработка страницы: " + url);
                page = Utils.getPageWithCache(httpClient, url, ENCODING, headers, ConfigurationUtils.getCurrentDirectory() + "cache/", isCache);
                inited = isInit(page);
                if (!inited) {
                    proxyInfos.remove(currentProxy);
                    Utils.removeCacheKey(url);
                    log.debug("IP заблокирован");
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                proxyInfos.remove(currentProxy);
                log.debug("Следующий прокси...");
            }
        }
        return page;
    }

    private boolean isInit(String page) {
        return !StringUtils.containsIgnoreCase(page, "Are you human?");
    }

}
