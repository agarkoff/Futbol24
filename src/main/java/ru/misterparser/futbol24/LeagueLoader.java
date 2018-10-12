package ru.misterparser.futbol24;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import ru.misterparser.common.Utils;
import ru.misterparser.common.configuration.ConfigurationUtils;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Created with IntelliJ IDEA.
 * User: Stas
 * Date: 04.02.14
 * Time: 23:39
 */
@Slf4j
class LeagueLoader extends Loader {

    @SuppressWarnings("FieldCanBeLocal")
    private Properties parserProperties = new Properties();

    LeagueLoader() {
        try {
            parserProperties.load(new FileInputStream(ConfigurationUtils.getCurrentDirectory() + "parser.properties"));
        } catch (FileNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Exception", e);
        }
        isCache = Boolean.parseBoolean(parserProperties.getProperty("isCache", "false"));
        Utils.setTryCount(1);
        Utils.setTimeoutIOError(2000);
    }

    Document getRootNode(String url, boolean ajax) throws InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (ajax) {
            headers.put("X-Requested-With", "XMLHttpRequest");
        }
        String page = fetch(url, headers);
        Thread.sleep(1000);
        return Jsoup.parse(page);
    }

    @Override
    public void run() {
        throw new NotImplementedException("Ошибка");
    }
}
