# Testes de Carga (k6)

Esta pasta contém cenários de teste de carga configurados para validar a resiliência e performance do `credit-hub`.
Os testes rodam via Docker Compose com a imagem do k6, atingindo a aplicação que deve estar rodando nativamente na máquina (`./gradlew :credit-hub-bootstrap:bootRun`).

> **Importante:** O gargalo dos testes locais quase sempre será o WireMock ou as limitações da própria máquina host (ex: I/O, threads, CPU), e não a aplicação Spring Boot em si. O valor real destes testes está na metodologia (avaliar se as proteções de resiliência desarmam corretamente) e na leitura dos resultados.

## Cenários

### 1. Latência Base (`01-latencia.js`)
- **O que prova:** Valida a latência em cenário de sucesso ("caminho feliz") para garantir que o *scatter-gather* consegue agregar a resposta dentro dos SLOs esperados sem bureaus lentos/com erro.
- **Como roda:** `docker compose run --rm k6 run /scripts/01-latencia.js`

### 2. Circuit Breaker (`02-breaker.js`)
- **O que prova:** Dispara requisições constantes para o CPF de erro 500 do Serasa (`00000000000`). O Circuit Breaker do Serasa deve abrir, evitando que a aplicação sobrecarregue o WireMock, enquanto os demais bureaus continuam respondendo com sucesso (agregando `Confianca.PARCIAL`).
- **Como roda:** `docker compose run --rm k6 run /scripts/02-breaker.js`

### 3. Bulkhead (`03-bulkhead.js`)
- **O que prova:** Satura as requisições para o CPF de timeout do Quod (`11111111111` - 8s de demora). Prova que o deadline global do scatter-gather (3s) cancela as requisições lentas, salvando as threads do sistema de ficarem presas em chamadas travadas.
- **Como roda:** `docker compose run --rm k6 run /scripts/03-bulkhead.js`

### 4. Capacidade (`04-capacidade.js`)
- **O que prova:** Aumenta gradualmente a taxa de requisições (de 10 até 500 req/s) utilizando CPFs de sucesso para descobrir em que momento a latência p95 passa a degradar (rompe o SLO).
- **Como roda:** `docker compose run --rm k6 run /scripts/04-capacidade.js`

## Dica: Observabilidade (Jaeger)

Lembre-se de rodar estes testes com a interface do Jaeger aberta (`http://localhost:16686`). 
Correlacionar os resultados do k6 (falhas, p95 de tempo de requisição) com o gráfico de "waterfall" dos *traces* no Jaeger ajuda a entender *onde* o tempo está sendo gasto durante o *scatter-gather* e *quando* a proteção (ex: o deadline de 3s) entra em ação.
