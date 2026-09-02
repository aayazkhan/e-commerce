package com.ecommerce.platform.kafka;

/** Invokes the JVM default implementation generated for the client interfaces. */
final class DefaultCloseInvoker {
    private DefaultCloseInvoker() {
    }

    static void closeConsumer(KafkaConsumerClient client) {
        KafkaConsumerClient.DefaultImpls.close(client);
    }

    static void closeProducer(KafkaProducerClient client) {
        KafkaProducerClient.DefaultImpls.close(client);
    }
}
