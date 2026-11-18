# API Gateway e Backend for Frontend

Um API Gateway e um BFF (Backend for Frontend) no mesmo processo reativo. O
**gateway** é a única porta de entrada: autentica, roteia para os serviços
internos reescrevendo o caminho, propaga um id de correlação, aplica timeout
por rota e repete só o que é seguro repetir. O **BFF** monta a tela de
dashboard de um cliente numa única resposta, buscando perfil, pedidos,
recomendações e nomes de produtos em quatro serviços diferentes.

Os serviços internos são simulados por um WireMock real, em container, com
atrasos e falhas controlados. Assim dá para medir o que importa num agregador:
quanto custa esperar, o que acontece quando uma das partes demora e quantas
chamadas uma tela realmente faz.

## Tecnologias

- **Java 21** e **Spring Boot 3.5**
- **Spring Cloud Gateway** (servidor WebFlux), com rotas em Java DSL
- **Spring WebFlux** e `WebClient` para as chamadas do BFF
- **Nimbus JOSE + JWT** para validar os tokens HS256
- **WireMock** fazendo o papel dos serviços internos, em container
- **Testcontainers** para subir o WireMock nos testes de integração
- **Gradle** (Kotlin DSL) com wrapper

## Gateway

| Rota pública | Serviço interno | Acesso | Timeout | Retry |
|---|---|---|---|---|
| `/api/users/**` | `/users-service/v1/users/**` | token | 1 s | GET em 502/503 |
| `/api/orders/**` | `/orders-service/v1/orders/**` | token | 1 s | nunca |
| `/api/catalog/**` | `/catalog-service/v1/products/**` | público | 1 s | GET em 502/503 |
| `/api/reports/**` | `/reports-service/v1/reports/**` | papel `analyst` | 5 s | nunca |

- **Filtros como `WebFilter`, não como `GlobalFilter`.** O filtro global do
  gateway só roda em requisição roteada, e o BFF é um controller local no
  mesmo processo. Autenticação, correlação e log de acesso precisam envolver
  os dois.
- **Identidade forjada.** Os serviços internos confiam no `X-User-Id` que o
  gateway envia. Se o cliente mandar o próprio `X-User-Id` ou `X-User-Roles`,
  o gateway remove antes de colocar os seus - inclusive nas rotas públicas. O
  `Authorization` não segue para dentro: os serviços confiam no gateway, o que
  pressupõe uma rede em que só ele os alcança.
- **Negar por padrão.** Qualquer caminho fora da lista de públicos exige token,
  então uma rota nova nasce fechada. Papéis são exigidos por prefixo.
- **Token validado com o algoritmo fixo.** O verificador aceita só HS256 e não
  lê o algoritmo do cabeçalho do token; `alg: none`, assinatura de outra chave,
  emissor diferente, token sem expiração ou vencido (além de 30 s de tolerância
  de relógio) dão `401`. O segredo com menos de 256 bits é recusado na
  subida.
- **O mesmo caminho para autenticar e para rotear.** `..`, `%2e`, `%2f`, `\` e
  `;` no caminho dão `400` antes de qualquer decisão. Sem isso,
  `/api/catalog/../users/1` parece público para o gateway e resolve para outro
  recurso mais adiante.
- **Correlação pelo contexto do Reactor.** Cada requisição recebe um
  `X-Request-Id`, que vai para o serviço interno, volta na resposta (inclusive
  nos erros dos filtros) e acompanha cada chamada que o BFF faz. Num servidor
  reativo a requisição troca de thread, então `ThreadLocal` e MDC não servem. Um
  id vindo do cliente só é aproveitado se tiver formato seguro, para não
  injetar nada no log.
- **Retry só no que é idempotente.** O filtro de retry, por padrão, repete
  qualquer 5xx e também timeout. Aqui ele repete apenas GET, apenas em `502` e
  `503` e em erro de I/O. Um `500` é bug e não melhora na segunda vez; repetir
  um timeout triplica a carga de um serviço que já está lento e faz o cliente
  esperar três timeouts. A rota de pedidos aceita POST e não tem retry: um
  POST repetido depois de um `503` é um segundo pedido.
- **Timeout por rota.** O padrão é 1 s; relatórios, que são lentos de verdade,
  têm 5 s. Estourou, o gateway responde `504`.
- **Log de acesso sem segredo.** Uma linha por requisição com id, método,
  caminho, status, duração, rota e usuário. Nunca o token, nunca a query
  string. A linha é escrita quando a resposta é enviada, para registrar o
  status que o tratador de erro escreveu e não o da cadeia de filtros.
- Cabeçalhos internos (`X-Internal-Debug`) são removidos das respostas.

## BFF

`GET /bff/dashboard` devolve perfil, pedidos com os nomes dos produtos e
recomendações. O usuário vem do token, nunca de um parâmetro.

- **Em paralelo.** As chamadas independentes saem juntas e a resposta espera
  só a mais lenta. Em sequência, espera a soma.
- **Degradação parcial.** O perfil é obrigatório: sem ele não há tela, e a
  resposta é `502`, `504` ou `404`. Pedidos, recomendações e catálogo são
  opcionais, cada um com o seu timeout (1 s, 300 ms e 500 ms). Quando um
  falha, a parte vem vazia e aparece em `degraded`, para o cliente distinguir
  "sem recomendações" de "recomendações indisponíveis". O timeout cancela a
  chamada em andamento.
- **Sem N+1.** Enriquecer cada linha de pedido com uma chamada ao catálogo
  multiplica as chamadas pelo número de linhas. O BFF deduplica os ids e busca
  em lote, em blocos de até 50 para não estourar o tamanho da URL.

`strategy=sequential` e `enrichment=per-item` existem só para comparar as duas
formas no mesmo processo.

## Como executar

Pré-requisitos: Docker e JDK 21.

```bash
docker compose up -d
./gradlew bootRun
```

O WireMock sobe na porta 8089 com os serviços internos já configurados em
`wiremock/mappings`, e o gateway na 8080. O script `scripts/token.sh` gera um
token assinado com o segredo de desenvolvimento.

### Exemplos

```bash
TOKEN=$(scripts/token.sh 42 customer)
```

Rota autenticada. O serviço simulado devolve o `X-User-Id` e o `X-Request-Id`
que recebeu - o `X-User-Id: 1` forjado chega como `42`:

```bash
curl -i localhost:8080/api/users/42 -H "Authorization: Bearer $TOKEN" -H 'X-User-Id: 1'
```

Rota pública, rota sem token e caminho com `..`:

```bash
curl localhost:8080/api/catalog/7
curl localhost:8080/api/orders
curl --path-as-is localhost:8080/api/catalog/../users/1
```

Relatório, que exige o papel `analyst`:

```bash
curl localhost:8080/api/reports/sales -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/reports/sales -H "Authorization: Bearer $(scripts/token.sh 7 analyst)"
```

Criar um pedido:

```bash
curl -X POST localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"productId":7,"quantity":1}'
```

Dashboard, nas duas formas:

```bash
curl localhost:8080/bff/dashboard -H "Authorization: Bearer $TOKEN"
curl 'localhost:8080/bff/dashboard?strategy=sequential&enrichment=per-item' \
  -H "Authorization: Bearer $TOKEN"
```

Deixar as recomendações lentas pela API do WireMock e ver o dashboard
degradar em 300 ms:

```bash
ID=$(curl -s localhost:8089/__admin/mappings \
  | jq -r '.mappings[] | select(.request.urlPath=="/recommendations-service/v1/recommendations") | .id')
curl -s localhost:8089/__admin/mappings/$ID | jq '.response.fixedDelayMilliseconds=3000' \
  | curl -X PUT localhost:8089/__admin/mappings/$ID -H 'Content-Type: application/json' -d @-
curl localhost:8080/bff/dashboard -H "Authorization: Bearer $TOKEN" | jq .degraded
```

## Testes

```bash
./gradlew test
```

São 64 testes. Os de integração usam um único container WireMock, iniciado por
inicializador estático e compartilhado por todas as classes; cada teste cria os
seus stubs e afirma sobre o journal do WireMock, ou seja, sobre o que de fato
chegou ao serviço interno. Os cenários cobrem a reescrita de caminho com query
string, negação por padrão, papel por prefixo, identidade forjada em rota
protegida e pública, token que não sai do gateway, `..` e `%2e%2e`, correlação
no fan-out do BFF, timeout por rota sem retry, retry de GET em `503`, `500` e
POST sem retry, as degradações de cada parte, N+1 contra lote, lote dividido em
blocos e o log de acesso sem token nem query string. Os unitários cobrem o
verificador de JWT (assinatura, `alg: none`, payload adulterado, expiração,
tolerância de relógio, emissor) e o saneamento do id de correlação.

## Medições

Setup: todas as medições foram feitas numa única sessão, com o jar empacotado
(`bootJar`, JDK 21), o daemon do Gradle parado e o WireMock 3.9 em container,
tudo no mesmo host (12 núcleos). Os atrasos de cada serviço são configurados
pela API de administração do WireMock antes de cada cenário. Antes das
medições, uma rodada de aquecimento de 1800 requisições foi descartada. O
cliente de carga é um script em Python que usa só a biblioteca padrão, com
conexão persistente; os números de chamadas saem do journal do WireMock.

### Custo do salto pelo gateway

1000 requisições sequenciais, serviço sem atraso:

| Caminho | p50 | p99 |
|---|---|---|
| Direto no WireMock | 0,3 ms | 0,4 ms |
| Pelo gateway, rota pública | 1,3 ms | 2,1 ms |
| Pelo gateway, rota com JWT | 1,0 ms | 1,7 ms |

O salto custa cerca de 1 ms. A validação do token não aparece na conta.

### Paralelo contra sequencial

Perfil 100 ms, pedidos 150 ms seguidos do catálogo a 50 ms, recomendações
200 ms. 200 requisições de cada:

| Estratégia | p50 | p99 |
|---|---|---|
| Sequencial | 512 ms | 515 ms |
| **Paralelo** | **208 ms** | **209 ms** |

Em sequência a tela paga 100 + 200 + 200. Em paralelo paga só o caminho mais
lento: pedidos mais catálogo, ou recomendações, os dois em 200 ms.

Com 64 clientes simultâneos por 20 s, o dashboard em paralelo atendeu 6151
requisições, 304 por segundo, com p50 de 206 ms e p99 de 334 ms - quatro
chamadas internas por requisição, cerca de 1200 por segundo, sem uma thread
bloqueada por chamada.

### Serviço opcional lento

Recomendações passam a responder em 3 s, o resto como acima:

| Timeout das recomendações | p50 | p99 | Resposta |
|---|---|---|---|
| **300 ms** | **304 ms** | **304 ms** | 200, `degraded: ["recommendations"]` |
| 10 s | 3006 ms | 3007 ms | 200, completa |

Sem um timeout próprio, a parte menos importante da tela dita o tempo da tela
inteira. Com ele, o usuário recebe o resto em 300 ms.

### N+1 no enriquecimento

5 pedidos com 4 linhas cada (20 linhas, 12 produtos distintos), catálogo com
50 ms por chamada, 4 chamadas simultâneas no modo por item. 200 requisições de
cada:

| Enriquecimento | Chamadas ao catálogo por dashboard | p50 | p99 |
|---|---|---|---|
| Uma por linha | 20 | 266 ms | 269 ms |
| **Lote deduplicado** | **1** | **57 ms** | **58 ms** |

No modo por item, a concorrência limitada vira cinco ondas de 50 ms. Subir o
limite reduz o tempo, mas não as 20 chamadas, que cada tela aberta despeja
sobre o catálogo.

### Timeout por rota

Serviço respondendo em 2 s, 20 requisições:

| Rota | Timeout | Resposta | p50 | Chamadas ao serviço |
|---|---|---|---|---|
| `/api/users/42` | 1 s | 20 × `504` | 1005 ms | 20 (timeout não é repetido) |
| `/api/reports/sales` | 5 s | 20 × `200` | 2004 ms | 20 |

### Retry só em GET

Serviço alternando `503` e sucesso, 100 requisições:

| Requisição | Respostas ao cliente | Chamadas ao serviço |
|---|---|---|
| `GET /api/users/42` | 100 × `200` (p50 29 ms) | 200 |
| `POST /api/orders` | 50 × `201`, 50 × `503` | 100 |

O GET esconde a falha do cliente ao custo de uma chamada extra e do backoff de
20 ms. O POST devolve a falha: só o cliente, com uma chave de idempotência,
poderia repetir com segurança.

## Estrutura

```
com.gkcontas.gateway
├── web        correlação, guarda de caminho, log de acesso e erros escritos pelos filtros
├── security   validação do JWT, filtro de autenticação, públicos e papéis
├── routing    rotas, reescrita, timeout por rota e retry
├── bff        dashboard: cliente dos serviços, agregação, degradação e enriquecimento
└── config     propriedades dos serviços internos e relógio
```
