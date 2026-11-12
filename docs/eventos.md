# Contrato de eventos

Exchange `eventhub.events` (topic, duravel). Produtor: Registration Service (via Transactional Outbox).
Entrega *at-least-once*: consumidores devem deduplicar por `eventId`.

## Envelope

```json
{
  "eventId": "uuid",
  "type": "RegistrationConfirmed",
  "version": 1,
  "occurredAt": "2026-11-06T14:00:00Z",
  "producer": "registration-service",
  "correlationId": "uuid ou id enviado pelo cliente",
  "payload": {
    "registrationId": "uuid",
    "activityId": "act-workshop",
    "participantId": "ana",
    "amount": 50.0,
    "reason": "DECLINED"
  }
}
```

## Eventos publicados

| type | routing key | Quando | `reason` |
| --- | --- | --- | --- |
| RegistrationConfirmed | registration.confirmed | Inscricao gratuita aceita ou pagamento aprovado | - |
| RegistrationReleased | registration.released | Reserva desfeita (pagamento recusado, indisponivel ou prazo vencido) | DECLINED, PAYMENT_UNAVAILABLE, HOLD_EXPIRED |
| RegistrationCancelled | registration.cancelled | Participante cancelou inscricao confirmada | - |

## Consumidores

| Fila | Binding | Servico | Observacao |
| --- | --- | --- | --- |
| notification.registration | registration.# | Notification (Python) | DLX `eventhub.dlx` -> fila `notification.dlq` |

Mudanca incompativel no payload = novo `version`, publicado em paralelo ate os consumidores migrarem.
