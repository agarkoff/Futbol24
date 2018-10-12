package ru.misterparser.futbol24;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.status.ErrorStatus;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.*;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class LogbackTextAreaAppender extends OutputStreamAppender<ILoggingEvent> {

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();

    private static JTextArea jTextArea = null;
    private static int maxLength = 10000000;

    public static void setTextArea(JTextArea jTextArea) {
        LogbackTextAreaAppender.jTextArea = jTextArea;
    }

    public static void setMaxLength(int maxLength) {
        LogbackTextAreaAppender.maxLength = maxLength;
    }

    @Override
    protected void append(ILoggingEvent event) {
        readLock.lock();
        try {
            final String str = new String(encoder.encode(event));
            SwingUtilities.invokeLater(() -> {
                if (jTextArea != null) {
                    if (event.getThrowableProxy() == null) {
                        jTextArea.append(str);
                    } else {
                        Throwable throwable = ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
                        jTextArea.append(StringUtils.trim(StringUtils.substringBefore(str, "\n")) + "\n");
                        jTextArea.append(ExceptionUtils.getStackTrace(throwable));
                    }
                    if (jTextArea.getText().length() > maxLength) {
                        String[] strings = StringUtils.split(jTextArea.getText(), "\n");
                        strings = Arrays.copyOfRange(strings, strings.length / 2, strings.length - 1);
                        jTextArea.setText("");
                        jTextArea.append(StringUtils.join(strings, "\n"));
                        jTextArea.append("\n");
                    }
                }
            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            readLock.unlock();
        }
    }

    public void start() {
        int errors = 0;
        if (this.encoder == null) {
            addStatus(new ErrorStatus("No encoder set for the appender named \"" + name + "\".", this));
            errors++;
        }
        // only error free appenders should be activated
        if (errors == 0) {
            started = true;
        }
    }
}