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

Padrões demonstrados: Database per Service, Saga orquestrada com compensação, Transactional Outbox,
consumidor idempotente, Publish/Subscribe, Circuit Breaker + timeout, Idempotency-Key e Correlation ID.

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
