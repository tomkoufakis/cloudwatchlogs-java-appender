package com.boxfuse.cloudwatchlogs.internal;

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.AnonymousAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.InputLogEvent;
import com.amazonaws.services.logs.model.InvalidSequenceTokenException;
import com.amazonaws.services.logs.model.PutLogEventsRequest;
import com.amazonaws.services.logs.model.PutLogEventsResult;
import com.amazonaws.services.logs.model.ResourceNotFoundException;
import com.boxfuse.cloudwatchlogs.CloudwatchLogsConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class CloudwatchLogsLogEventPutter implements Runnable {
    private static final int MAX_FLUSH_DELAY = 500 * 1000 * 1000;
    private static final int MAX_BATCH_COUNT = 10000;
    private static final int MAX_BATCH_SIZE = 1048576;

    private final CloudwatchLogsConfig config;
    private final BlockingQueue<CloudwatchLogsLogEvent> eventQueue;
    private final AWSLogs logsClient;
    private final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final boolean enabled;
    private boolean running;
    private String app;
    private String logGroupName;
    private int batchSize;
    private long lastFlush;
    private List<InputLogEvent> eventBatch;
    private String nextSequenceToken;
    private final AtomicLong processedCount = new AtomicLong(0);

    /**
     * Creates a new EventPutter for the current AWS region.
     *
     * @param config     The config to use.
     * @param eventQueue The event queue to consume from.
     * @return The new EventPutter.
     */
    public static CloudwatchLogsLogEventPutter create(CloudwatchLogsConfig config, BlockingQueue<CloudwatchLogsLogEvent> eventQueue) {
        boolean enabled = config.getRegion() != null || config.getEndpoint() != null;
        AWSLogs logsClient = enabled ? createLogsClient(config) : null;
        return new CloudwatchLogsLogEventPutter(config, eventQueue, logsClient, enabled);
    }

    /**
     * For internal use only. This constructor lets us switch the AWSLogs implementation for testing.
     */
    public CloudwatchLogsLogEventPutter(CloudwatchLogsConfig config, BlockingQueue<CloudwatchLogsLogEvent> eventQueue,
                                        AWSLogs awsLogs, boolean enabled) {
        this.config = config;
        logGroupName = "boxfuse/" + config.getEnv();
        String image = config.getImage();
        app = image.substring(0, image.indexOf(":"));
        this.eventQueue = eventQueue;
        this.enabled = enabled;
        logsClient = awsLogs;
    }

    static AWSLogs createLogsClient(CloudwatchLogsConfig config) {
        AWSLogsClientBuilder builder = AWSLogsClientBuilder.standard();
        if (config.getEndpoint() != null) {
            // Non-AWS mock endpoint
            builder.setCredentials(new AWSStaticCredentialsProvider(new AnonymousAWSCredentials()));
            builder.setEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(config.getEndpoint(), config.getRegion()));
        } else {
            builder.setRegion(config.getRegion());
        }
        return builder.build();
    }

    /**
     * @return The number of log events that have been processed by this putter.
     */
    public long getProcessedCount() {
        return processedCount.get();
    }

    @Override
    public void run() {
        if (!enabled && !config.isStdoutFallback()) {
            System.out.println("WARNING: AWS CloudWatch Logs appender is disabled (Unable to detect the AWS region and no CloudWatch Logs endpoint specified)");
            return;
        }

        running = true;
        nextSequenceToken = null;
        eventBatch = new ArrayList<>();
        batchSize = 0;
        lastFlush = System.nanoTime();

        while (running) {
            CloudwatchLogsLogEvent event = eventQueue.poll();
            if (event != null) {
                Map<String, Object> eventMap = new TreeMap<>();
                eventMap.put("instance", config.getInstance());
                eventMap.put("image", config.getImage());
                eventMap.put("level", event.getLevel());
                eventMap.put("event", event.getEvent());
                eventMap.put("message", event.getMessage());
                eventMap.put("logger", event.getLogger());
                eventMap.put("thread", event.getThread());
                eventMap.put("account", event.getAccount());
                eventMap.put("action", event.getAction());
                eventMap.put("user", event.getUser());
                eventMap.put("session", event.getSession());
                eventMap.put("request", event.getRequest());

                String eventJson;
                try {
                    eventJson = toJson(eventMap);
                } catch (JsonProcessingException e) {
                    printWithTimestamp(new Date(), "Unable to serialize log event: " + eventMap);
                    continue;
                }

                // Source: http://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
                // The maximum batch size is 1,048,576 bytes,
                int eventSize =
                        // and this size is calculated as the sum of all event messages in UTF-8,
                        eventJson.getBytes(StandardCharsets.UTF_8).length
                                // plus 26 bytes for each log event.
                                + 26;

                if (eventSize > MAX_BATCH_SIZE) {
                    printWithTimestamp(new Date(), "Unable to send log event as its size (" + eventSize + " bytes)"
                            + " exceeds the maximum size supported by AWS CloudWatch Logs (" + MAX_BATCH_SIZE + " bytes): " + eventMap);
                    continue;
                }

                if (config.isDebug()) {
                    printWithTimestamp(new Date(), "Event Size: " + eventSize + " bytes, Batch Size: " + batchSize
                            + " bytes, Batch Count: " + eventBatch.size() + ", Event: " + eventJson);
                }

                if ((eventBatch.size() + 1) >= MAX_BATCH_COUNT || (batchSize + eventSize) >= MAX_BATCH_SIZE) {
                    flush();
                }

                eventBatch.add(new InputLogEvent().withMessage(eventJson).withTimestamp(event.getTimestamp()));
                batchSize += eventSize;
            } else {
                if (!eventBatch.isEmpty() && isTimeToFlush()) {
                    flush();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    running = false;
                }
            }
        }
    }

    private boolean isTimeToFlush() {
        return lastFlush <= (System.nanoTime() - MAX_FLUSH_DELAY);
    }

    private void flush() {
        Collections.sort(eventBatch, new Comparator<InputLogEvent>() {
            @Override
            public int compare(InputLogEvent o1, InputLogEvent o2) {
                return o1.getTimestamp().compareTo(o2.getTimestamp());
            }
        });
        if (config.isStdoutFallback()) {
            for (InputLogEvent event : eventBatch) {
                printWithTimestamp(new Date(event.getTimestamp()), logGroupName + " " + app + " " + event.getMessage());
            }
        } else {
            int retries = 15;
            do {
                PutLogEventsRequest request =
                        new PutLogEventsRequest(logGroupName, app, eventBatch).withSequenceToken(nextSequenceToken);
                try {
                    long start = 0;
                    if (config.isDebug()) {
                        start = System.nanoTime();
                    }
                    PutLogEventsResult result = logsClient.putLogEvents(request);
                    if (config.isDebug()) {
                        long stop = System.nanoTime();
                        long elapsed = (stop - start) / 1000000;
                        printWithTimestamp(new Date(), "Sending " + eventBatch.size() + " events took " + elapsed + " ms");
                    }
                    processedCount.addAndGet(request.getLogEvents().size());
                    nextSequenceToken = result.getNextSequenceToken();
                    break;
                } catch (InvalidSequenceTokenException e) {
                    nextSequenceToken = e.getExpectedSequenceToken();
                } catch (ResourceNotFoundException e) {
                    printWithTimestamp(new Date(), "Unable to send logs to AWS CloudWatch Logs at "
                            + logGroupName + ">" + app + " (" + e.getErrorMessage() + "). Dropping log events batch ...");
                    break;
                } catch (SdkClientException e) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        // Ignore
                    }
                    if (--retries == 0) {
                        printWithTimestamp(new Date(), "Unable to send logs to AWS CloudWatch Logs ("
                                + e.getMessage() + "). Dropping log events batch ...");
                    }
                }
            } while (retries > 0);
        }
        eventBatch = new ArrayList<>();
        batchSize = 0;
        lastFlush = System.nanoTime();
    }

    /* private -> for testing */ String toJson(Map<String, Object> eventMap) throws JsonProcessingException {
        // Compensate for https://github.com/FasterXML/jackson-databind/issues/1442
        Map<String, Object> nonNullMap = new TreeMap<>();
        for (Map.Entry<String, Object> entry : eventMap.entrySet()) {
            if (entry.getValue() != null) {
                nonNullMap.put(entry.getKey(), entry.getValue());
            }
        }
        return objectMapper.writeValueAsString(nonNullMap);
    }

    private void printWithTimestamp(Date date, String str) {
        System.out.println(new SimpleDateFormat("YYYY-MM-dd HH:mm:ss.SSS").format(date) + " " + str);
    }

    public void terminate() {
        running = false;
    }
}
