# Decisões de Arquitetura (DECISIONS.md)

## 1. Por que usar Transactional Outbox em vez de publicar direto no Kafka dentro do @Transactional?

**Contexto**: Durante a consulta de crédito, precisamos salvar a trilha de auditoria e garantir que o evento `ConsultaCreditoRealizada` seja publicado no Kafka para sistemas subsequentes (audit-service, decisão de crédito, perfil).

**A Decisão**: Utilizamos o padrão **Transactional Outbox**, onde o evento é salvo em uma tabela `outbox` na mesma transação `@Transactional` da operação, em vez de publicar diretamente no Kafka (`kafkaTemplate.send(...)` dentro da transação).

**Justificativa**:
Publicar diretamente em um message broker dentro de uma transação de banco de dados cria o problema de **Dual Write** (Escrita Dupla):
1. Se publicarmos no Kafka antes do commit do banco, o Kafka já terá recebido o evento. Se o commit falhar em seguida (ex: erro de constraints, falha de conexão), o sistema consumidor processará um evento de um estado que foi revertido.
2. Se publicarmos após o commit (ex: via `@TransactionalEventListener(phase = AFTER_COMMIT)`), se o processo falhar (crash da aplicação) logo após o commit do banco mas antes de atingir o Kafka, a informação é atualizada localmente, mas o evento nunca é disparado, resultando em perda do evento.

Com o **Transactional Outbox**, garantimos atomicidade: a tabela de outbox e o estado da aplicação estão no mesmo banco, então ou ambos são salvos ou nenhum é. Um processo assíncrono (relay) lê os eventos da tabela e garante que sejam entregues ao Kafka (at-least-once delivery).

**Consequências**:
- Garantia forte de consistência eventual sem perda de eventos.
- Requer uma thread/job adicional (`@Scheduled`) no adapter para realizar o relay das mensagens do banco para o Kafka.
- Consumidores precisam ser **idempotentes**, pois a entrega é *at-least-once* (pode haver duplicação se o relay falhar ao apagar da tabela outbox após envio). Implementamos a deduplicação no `audit-service` usando o `queryId` como chave primária.
