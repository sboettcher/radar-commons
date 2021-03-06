package org.radarcns.producer;

import org.radarcns.data.RecordData;

import java.io.Closeable;
import java.io.IOException;

public interface KafkaTopicSender<K, V> extends Closeable {
    /**
     * Send a message to Kafka eventually.
     *
     * @param key key of a kafka record to send
     * @param value value of a kafka record to send
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void send(K key, V value) throws IOException;

    /**
     * Send a message to Kafka eventually.
     *
     * Contained offsets must be strictly monotonically increasing
     * for subsequent calls.
     *
     * @param records records to send.
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void send(RecordData<K, V> records) throws IOException;

    /**
     * Clears any messages still in cache.
     */
    void clear();

    /**
     * Flush all remaining messages.
     *
     * @throws AuthenticationException if the client failed to authenticate itself
     * @throws IOException if the client could not send a message
     */
    void flush() throws IOException;
}
