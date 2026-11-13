# EventHub — Trabalho 2 (Wave 4: Microsserviços)

**Arquitetura de Software · UECE** · Grupo: José Waldeney, Renan Teixeira, João Carlos

> **Relatório técnico:** [docs/relatorio-tecnico.md](docs/relatorio-tecnico.md) (também em [PDF](docs/relatorio-tecnico.pdf)): decomposição, propriedade dos dados, padrões, 5 ADRs, implementação, validação e reflexão crítica.

Protótipo da plataforma de eventos EventHub. Ele implementa o fluxo de **inscrição paga** de ponta a ponta,
com 4 microsserviços em **2 tecnologias** (Java 21 e Python/FastAPI), PostgreSQL com um schema por serviço
e RabbitMQ para eventos.

| Serviço | Tecnologia | Porta | Responsabilidade | Dados (schema) |
| --- | --- | --- | --- | --- |
| `catalog` | Python / FastAPI | 8001 | Eventos e atividades, login de demonstração (JWT) | `catalog` |
| `registration` | **Java 21** (sem framework) | 8080 | Inscrições, vagas, Saga do pagamento, Outbox | `registration` |
| `payment` | Python / FastAPI | 8002 | Pagamentos + provedor externo **simulado** | `payment` |
| `notification` | Python (consumidor RabbitMQ) | 8003 | E-mails (simulados) a partir de eventos | `notification` |
| `web` | nginx + HTML/JS | 3000 | Front de demonstração e gateway simples (só roteia `/api/<serviço>/`) | — |

Modelados no relatório, mas fora do protótipo: Check-in, Certificados, Relatórios, Keycloak e lista de espera.

## Fluxo implementado

```
Cliente ──REST──▶ Registration (Java) ──REST──▶ Catalog (Python)       capacidade e preço (1ª vez)
                       │  UPDATE condicional atômico reserva a vaga
                       ├──REST + timeout 2s + Circuit Breaker──▶ Payment (Python) ──▶ provedor simulado
                       │  confirma OU libera a vaga (compensação)
                       └── outbox ──▶ RabbitMQ (eventhub.events) ──▶ Notification (Python)
```

Os padrões usados e o que cada serviço resolve estão explicados em [Arquitetura](#arquitetura), logo abaixo.

## Arquitetura

O EventHub foi dividido a partir de **forças**, e não de um serviço por requisito. Quatro forças decidiram as fronteiras:

- picos de **20×** nas inscrições (RQ02);
- **zero overbooking** (RN01/RQ04);
- serviços periféricos que **podem cair** sem parar as inscrições (RQ03/RN05);
- **duas tecnologias** conversando por contratos (RQ05).

A regra que guia tudo: **só a Inscrição decide vagas; o resto reage a eventos.** O raciocínio completo, as alternativas rejeitadas e os 5 ADRs estão no [relatório técnico](docs/relatorio-tecnico.md).

> Siglas do enunciado: **RF** = requisito funcional (o que o sistema faz), **RN** = regra de negócio (o que nunca pode ser violado), **RQ** = requisito de qualidade (como o sistema precisa se comportar).

### O que cada serviço resolve

| Serviço | Requisitos que atende | Como atende |
| --- | --- | --- |
| **Inscrição** (`registration`, Java 21) | RF05, RF06, RN01, RN02, RN03, RN04, RQ04 | É o **único dono das vagas**. Reserva a vaga com um `UPDATE` atômico, coordena o pagamento (Saga), confirma ou libera a vaga e grava cada mudança como evento na Outbox. É a parte crítica do sistema, por isso foi feita em Java, com cada mecanismo explícito no código. |
| **Catálogo** (`catalog`, Python/FastAPI) | RF02, RF03, RF04, RN07 (organizador), RF01 (login simplificado) | Dono de eventos e atividades: nome, data, preço e capacidade **configurada**. Um organizador só cria atividade da própria organização. No protótipo, também emite o token JWT de login. |
| **Pagamento** (`payment`, Python/FastAPI) | RF07, RN02, RN03 | Isola o **provedor externo instável**: se o provedor for lento ou estiver fora, só o Pagamento sofre. Um pagamento por inscrição (idempotente) e cancelamento/estorno quando a Inscrição desiste. |
| **Notificação** (`notification`, Python + pika) | RF08, RN05, RQ01, RQ03 | Só **reage a eventos** do RabbitMQ. Se estiver fora do ar, as mensagens esperam na fila e a inscrição continua confirmada. Pode mudar e ir para produção sem tocar no resto. |
| **web** (nginx + HTML/JS) | RQ06, RQ08 (demonstração) | Front da demonstração e **gateway simples**: o navegador fala com um endereço só e o nginx encaminha `/api/<serviço>/` para cada serviço. |
| **PostgreSQL** | RQ01, RQ04 | Um banco com **um schema e um usuário por serviço**, sem permissão cruzada. A regra "sem overbooking" também está no próprio banco, com um `CHECK`. |
| **RabbitMQ** | RQ03, RN05 | O **intermediário de mensagens**: desacopla quem produz um evento (Inscrição) de quem reage a ele (Notificação). |
| *Só modelados:* Identidade (Keycloak), Check-in, Certificados, Relatórios | RF01, RF09, RF10, RF11, RN06 | Estão no desenho do relatório, mas não no código. A lista de espera (RF06/RN04) também ficou só modelada. |

### Técnicas de microsserviços usadas

Cada técnica entrou para resolver um requisito, não "por boa prática".

| Técnica | O que significa, em linguagem simples | Por que usamos | Onde está |
| --- | --- | --- | --- |
| **Database per Service** | Cada serviço é dono dos seus dados e ninguém lê a tabela do outro. Quem precisa de um dado alheio pergunta pela API do dono ou guarda uma cópia. | Evoluir cada serviço sem quebrar os outros (RQ01) | `infra/init-db.sh` |
| **Reserva atômica** | A vaga é reservada com um único comando SQL que só funciona se ainda houver vaga: `UPDATE … WHERE reservadas + confirmadas < capacidade`. Se afetar 0 linhas, acabou a vaga. O banco serializa a disputa. | Zero overbooking, mesmo com 200 pessoas ao mesmo tempo (RN01/RQ04) | `RegistrationService.java` e o `CHECK seats_invariant` em `schema.sql` |
| **Saga orquestrada** | Uma "transação longa" quebrada em passos locais (reservar → pagar → confirmar), cada um com seu próprio commit. Um serviço (a Inscrição) comanda a sequência. | Inscrição e Pagamento têm bancos diferentes, então não existe um commit único entre eles (RN02/RQ04) | `RegistrationService.java` (`register`, `confirm`, `release`) |
| **Compensação** | O "desfazer" de um passo da Saga. Se o pagamento falha, a Inscrição libera a vaga e pede ao Pagamento para cancelar ou estornar. | Nenhuma vaga presa e nenhuma cobrança indevida (RN03) | `release()` + `POST /payments/{id}/cancel` |
| **Transactional Outbox** | O evento ("inscrição confirmada") é gravado numa tabela `outbox`, **na mesma transação** da inscrição. Um processo separado publica no RabbitMQ e só marca como enviado depois que o broker confirma. | Se o serviço cair ou o RabbitMQ estiver fora, o evento não se perde (RN05/RQ04) | Tabela `outbox` + `OutboxRelay.java` |
| **Publish/Subscribe** | Um serviço publica um evento e quem tiver interesse recebe. O produtor não sabe quem são os consumidores. | A Inscrição não depende da Notificação estar no ar (RQ03) | Exchange `eventhub.events` (RabbitMQ) |
| **Consumidor idempotente** | Se a mesma mensagem chegar duas vezes, o efeito acontece uma vez só (o e-mail sai uma vez). | O RabbitMQ garante entregar "pelo menos uma vez", às vezes duas | `notification/app.py` (`event_id` único) |
| **Idempotency-Key** | O cliente manda uma chave única no pedido. Repetir o mesmo pedido devolve o mesmo resultado, sem criar uma segunda inscrição. | Clique duplo ou rede instável não duplicam a inscrição | Header `Idempotency-Key` na Inscrição |
| **Dead Letter Queue (DLQ)** | Uma fila separada para mensagens que não conseguiram ser processadas, para não travarem a fila principal. | Uma mensagem com defeito não para as notificações | Fila `notification.dlq` |
| **Timeout** | O tempo máximo de espera numa chamada a outro serviço (2 s). Passou disso, a Inscrição desiste e libera a vaga. | Um provedor lento não segura o usuário nem a vaga (RQ03) | `PaymentClient.java` |
| **Circuit Breaker** | Um "disjuntor": depois de 5 falhas seguidas, a Inscrição **para de chamar** o Pagamento por 15 s e responde erro na hora; depois testa uma chamada e, se der certo, volta ao normal. | Não acumular requisições presas esperando um serviço morto no pico (RQ02/RQ03) | `CircuitBreaker.java` (estados CLOSED → OPEN → HALF_OPEN) |
| **Correlation ID** | Um identificador que acompanha a requisição por todos os serviços e eventos. Com ele, dá para achar a mesma inscrição nos logs de Java e de Python. | Rastrear e diagnosticar uma inscrição de ponta a ponta (RQ06) | Header `X-Correlation-Id` → evento → logs |
| **Projeção local** | Guardar uma cópia do dado de outro serviço. A Inscrição guarda capacidade e preço da atividade depois de consultar o Catálogo uma vez. | O caminho mais carregado não depende do Catálogo a cada inscrição (RQ02) | Tabela `activity_seats` |
| **Contratos explícitos** | As interfaces entre serviços são documentadas: OpenAPI para REST e um envelope versionado para eventos. | Java e Python conversam sem depender de detalhes internos (RQ05) | `/docs` (FastAPI), `docs/openapi-registration.yaml`, `docs/eventos.md` |
| **Token de acesso (JWT)** | Um "crachá" digital assinado que diz quem é o usuário e qual o seu papel. Cada serviço confere o crachá e só deixa cada um mexer no que é seu. | Acesso autenticado e autorizado (RN07/RQ07) | `Jwt.java`, `catalog/app.py` |
| **API Gateway (simplificado)** | Uma porta de entrada única para o cliente. Aqui só encaminha; no desenho também validaria token e limitaria a taxa de acesso. | Um endereço só para o front | `web/nginx.conf` |
| **Health check** | Um endpoint `/health` que diz se o serviço está vivo. | Ver o estado de cada serviço (painel do front) | `GET /health` em todos |

**Avaliados e não adotados:** *Two-Phase Commit* (trava todos os serviços se um falhar), *Saga coreografada* (espalharia o estado do pagamento entre serviços), *Event Sourcing* (sem necessidade de reconstruir histórico), *Service Discovery* (o DNS do Docker Compose já resolve os nomes).

### Requisito de qualidade → técnica

| Requisito | Técnica que o atende | Como foi comprovado |
| --- | --- | --- |
| **RQ01** Evolução independente | Database per Service + Publish/Subscribe | Cada serviço tem o próprio Dockerfile e o próprio schema |
| **RQ02** Escalabilidade seletiva | Reserva atômica no banco + projeção local | 200 tentativas para 30 vagas em ~1 s: exatamente 30 confirmadas |
| **RQ03** Disponibilidade | Publish/Subscribe + Timeout + Circuit Breaker | Notificação, RabbitMQ e Pagamento derrubados durante os testes |
| **RQ04** Consistência | Reserva atômica + Saga + Compensação + Outbox | Recusa e timeout nunca deixaram vaga presa nem cobrança |
| **RQ05** Interoperabilidade | Contratos explícitos (OpenAPI + eventos) | Fluxo Java ↔ Python ponta a ponta |
| **RQ06** Observabilidade | Correlation ID | Mesma inscrição rastreada nos logs de 3 serviços |
| **RQ07** Segurança | JWT validado em cada serviço; `.env` fora do git | Testes de 401 e 403 |
| **RQ08** Operabilidade | Docker Compose + README + pytest | `docker compose up` e 14 testes passando |

## Como rodar

Pré-requisitos: Docker com Docker Compose e, para os testes e o `demo.py`, Python 3 com venv (`sudo apt install python3-venv` no Ubuntu).

```bash
cp .env.example .env
docker compose up --build -d
docker compose ps
```

Documentação das APIs: http://localhost:8001/docs (Catalog), http://localhost:8002/docs (Payment),
`docs/openapi-registration.yaml` (Registration) e `docs/eventos.md` (contrato dos eventos).
Painel do RabbitMQ: http://localhost:15672 (usuário e senha do `.env`).

### Front de demonstração

Abra **http://localhost:3000**.

- **Inscrição ao vivo:** login (`ana` ou `bruno`), atividades com barra de vagas atualizada a cada 3 s e escolha do cartão simulado. Uma linha do tempo mostra a requisição passando por Inscrição (Java), Pagamento e Notificação (Python), com o mesmo correlationId. Também dá para cancelar inscrições.
- **Serviços e APIs:** status de cada serviço, endpoints, links para o Swagger e contrato dos eventos do RabbitMQ.
- **Painel Monitor (lateral, sempre visível):** saúde e latência dos serviços, circuit breaker, vagas das atividades de teste e fluxo de eventos do RabbitMQ.
- **Para mostrar falhas:** com a página aberta, rode `docker compose stop notification` ou `docker compose stop payment`. O cabeçalho mostra o serviço vermelho e o estado do circuit breaker. Depois, `docker compose start ...`.

### Demonstração guiada no terminal (alternativa)

```bash
python3 -m venv .venv && source .venv/bin/activate   # Linux/macOS (Windows: .venv\Scripts\activate)
pip install -r tests/requirements.txt
python demo.py                # 10 passos, ENTER avança
```

Usuários de demonstração (senha `DEMO_PASSWORD` do `.env`): `ana`, `bruno` (participantes), `org-uece`, `org-ufc` (organizadores).
O cartão simulado define o comportamento do provedor: `tok_ok` aprova, `tok_declined` recusa, `tok_slow` demora 5 s.

### Testes de validação (Parte III)

```bash
# com o .venv ativado
pytest tests -v -s            # rodar na raiz do repositório
```

| Arquivo | O que prova |
| --- | --- |
| `tests/test_1_fluxos.py` | Fluxo pago e gratuito, recusa, timeout com compensação, idempotência, cancelamento, segurança (RN07) |
| `tests/test_2_concorrencia.py` | 200 tentativas simultâneas para 30 vagas: exatamente 30 confirmadas |
| `tests/test_3_falhas.py` | Notificação fora, RabbitMQ fora e Pagamento fora (circuito abre e fecha). Usa `docker compose stop/start` |

Os resultados de uma execução estão em `evidencias/`.

**Acompanhar os testes ao vivo:** abra http://localhost:3000 e rode o `pytest` num terminal ao lado. O painel **Monitor**, à direita (abre e fecha pelo botão no cabeçalho), mostra os serviços com latência, as vagas das atividades criadas pelos testes atualizando a cada segundo e o fluxo de eventos do RabbitMQ com contadores. No teste de concorrência a barra para em 30; nos testes de falha o serviço fica vermelho e o circuito abre.

Para rastrear uma inscrição entre serviços:

```bash
docker compose logs | grep <correlationId>
```

## Recortes assumidos (e por quê)

- **Autenticação:** JWT HS256 emitido pelo Catálogo, com segredo compartilhado via `.env`. O desenho alvo
  (ADR-05) usa Keycloak/OIDC. Foi simplificado para caber no prazo, mantendo a validação do token e a regra de dono (RN07).
- **Gateway simplificado:** o nginx do `web` só roteia `/api/<serviço>/` (sem validar token nem limitar taxa); cada serviço valida o JWT. Não muda nenhuma decisão de consistência demonstrada.
- **Pagamento síncrono:** o provedor simulado responde na hora (ou estoura o timeout). A expiração de
  reservas (RN02) existe como job a cada 10 s e protege contra falhas no meio da Saga.
- **Registration sem Spring:** usa o servidor HTTP do próprio JDK, JDBC e o cliente RabbitMQ. Menos
  dependências e cada mecanismo (Outbox, Circuit Breaker, transações) fica explícito no código.
- **Um PostgreSQL:** uma instância, com um schema e um usuário por serviço, sem permissões cruzadas.

## Estrutura

```
catalog/        Python  - app.py
payment/        Python  - app.py
notification/   Python  - app.py
registration/   Java 21 - src/main/java/br/uece/eventhub/registration/
infra/          init do Postgres (schemas e usuários por serviço)
web/            front de demonstração (index.html) + nginx (gateway simples)
tests/          testes de validação (pytest)
evidencias/     saída dos testes e trechos de log
docs/           relatório técnico (MD + PDF), diagramas e contratos (OpenAPI, eventos)
demo.py         roteiro da demonstração
```

## Uso de IA generativa

O grupo usou o Claude (Anthropic) como apoio para propor a decomposição, rascunhar os ADRs, gerar o código inicial dos serviços, do front e dos testes, e redigir o relatório. Todo o conteúdo foi revisado, executado e validado pelo grupo (os resultados estão em evidencias/). As referências citadas no relatório foram conferidas.
