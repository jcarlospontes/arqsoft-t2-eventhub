package br.uece.eventhub.registration;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Relay da Transactional Outbox (ADR-04).
 * Publica no RabbitMQ os eventos gravados junto com o estado e so os marca como publicados depois que o
 * broker confirma (publisher confirms). Se o broker estiver fora, ou nenhuma fila estiver ligada ao evento,
 * os eventos ficam na outbox e sao reenviados depois: entrega at-least-once.
 */
final class OutboxRelay {

    static final String EXCHANGE = "eventhub.events";

    private final Db db;
    private final ConnectionFactory factory = new ConnectionFactory();
    private Connection connection;
    private Channel channel;
    private final AtomicBoolean returned = new AtomicBoolean(false);
    private boolean brokerDown = false;

    OutboxRelay(Db db, String host, String user, String password) {
        this.db = db;
        factory.setHost(host);
        factory.setUsername(user);
        factory.setPassword(password);
        factory.setConnectionTimeout(2000);
        factory.setAutomaticRecoveryEnabled(false);
    }

    synchronized void publishPending() {
        try {
            int sent = db.tx(c -> {
                List<Map<String, Object>> batch = Db.query(c, """
                        SELECT id, event_id, event_type, routing_key, payload FROM outbox
                        WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED""");
                if (batch.isEmpty()) return 0;
                Channel ch = channel();
                returned.set(false);
                for (Map<String, Object> e : batch) {
                    AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                            .contentType("application/json")
                            .deliveryMode(2) // persistente
                            .messageId(e.get("event_id").toString())
                            .type((String) e.get("event_type"))
                            .build();
                    // mandatory=true: se nenhuma fila receber, o broker devolve e tentamos de novo depois
                    try {
                        ch.basicPublish(EXCHANGE, (String) e.get("routing_key"), true, props,
                                ((String) e.get("payload")).getBytes(StandardCharsets.UTF_8));
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException("falha ao publicar: " + ex.getMessage(), ex);
                    }
                }
                try {
                    ch.waitForConfirmsOrDie(5000);
                } catch (Exception ex) {
                    throw new IllegalStateException("broker nao confirmou: " + ex.getMessage(), ex);
                }
                if (returned.get()) throw new IllegalStateException("evento sem fila consumidora; mantido na outbox");
                for (Map<String, Object> e : batch) {
                    Db.update(c, "UPDATE outbox SET published_at = now() WHERE id = ?", e.get("id"));
                }
                return batch.size();
            });
            if (brokerDown) Log.info("[outbox] broker disponivel novamente; publicacao retomada");
            brokerDown = false;
            if (sent > 0) Log.info("[outbox] " + sent + " evento(s) publicado(s) em " + EXCHANGE);
        } catch (Exception e) {
            if (!brokerDown) Log.info("[outbox] falha ao publicar, eventos permanecem na outbox: " + e.getMessage());
            brokerDown = true;
            closeQuietly();
        }
    }

    private Channel channel() {
        try {
            if (channel != null && channel.isOpen()) return channel;
            closeQuietly();
            connection = factory.newConnection("registration-outbox");
            channel = connection.createChannel();
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            channel.confirmSelect();
            channel.addReturnListener(r -> returned.set(true));
            return channel;
        } catch (Exception e) {
            throw new IllegalStateException("broker indisponivel (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private void closeQuietly() {
        try { if (connection != null) connection.abort(); } catch (Exception ignored) { }
        connection = null;
        channel = null;
    }
}
