# 필드 이름 목록 — S0_normal

정상 트래픽에서 나온 필드 이름만 적었다. 값은 없다. 횟수는 그 필드가 붙은 레코드(span 또는 log) 수다.
장애 스위치 흔적(flagd·flagd-ui 서비스, `feature_flag.*` 키)은 모든 조건에서 빠지므로 여기서도 뺐다.

## 서비스별 레코드 수

| 서비스 | span | log |
| --- | ---: | ---: |
| accounting | 43 | 14 |
| ad | 106 | 51 |
| cart | 620 | 229 |
| checkout | 190 | 84 |
| currency | 137 | 132 |
| email | 56 | 14 |
| fraud-detection | 28 | 14 |
| frontend | 3527 | 503 |
| frontend-proxy | 3450 | 1779 |
| frontend-web | 1649 | 0 |
| image-provider | 431 | 0 |
| kafka | 0 | 236 |
| load-generator | 531 | 492 |
| otelcol-contrib | 0 | 801 |
| payment | 28 | 28 |
| product-catalog | 1380 | 670 |
| quote | 75 | 25 |
| recommendation | 285 | 182 |
| shipping | 64 | 64 |
| telemetry-docs | 61 | 0 |

## 필드

| 위치 | 필드 | 횟수 | 나온 서비스 |
| --- | --- | ---: | --- |
| 리소스 | `browser.language` | 1649 | frontend-web |
| 리소스 | `cluster_name` | 1779 | frontend-proxy |
| 리소스 | `container.id` | 5527 | accounting, ad, cart, fraud-detection, frontend, kafka, payment, quote |
| 리소스 | `docker.cli.cobra.command_path` | 13258 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, payment, product-catalog, quote, recommendation, shipping |
| 리소스 | `host.arch` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.cpu.cache.l2.size` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.cpu.family` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.cpu.model.id` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.cpu.model.name` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.cpu.stepping` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.cpu.vendor.id` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `host.name` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `log_name` | 1779 | frontend-proxy |
| 리소스 | `node_name` | 1779 | frontend-proxy |
| 리소스 | `os.build_id` | 57 | accounting |
| 리소스 | `os.description` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `os.name` | 157 | accounting, quote |
| 리소스 | `os.type` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `os.version` | 4678 | accounting, ad, fraud-detection, frontend, kafka, payment, quote |
| 리소스 | `process.args_count` | 100 | quote |
| 리소스 | `process.command` | 4256 | email, frontend, payment, quote |
| 리소스 | `process.command_args` | 4446 | checkout, fraud-detection, frontend, payment, shipping |
| 리소스 | `process.command_line` | 393 | ad, kafka |
| 리소스 | `process.creation.time` | 57 | accounting |
| 리소스 | `process.executable.name` | 4276 | checkout, frontend, payment |
| 리소스 | `process.executable.path` | 4811 | ad, checkout, fraud-detection, frontend, kafka, payment, quote |
| 리소스 | `process.owner` | 4433 | accounting, checkout, frontend, payment, quote |
| 리소스 | `process.pid` | 5066 | accounting, ad, checkout, email, fraud-detection, frontend, kafka, payment, quote, shipping |
| 리소스 | `process.runtime.description` | 4966 | accounting, ad, checkout, email, fraud-detection, frontend, kafka, payment, shipping |
| 리소스 | `process.runtime.name` | 5066 | accounting, ad, checkout, email, fraud-detection, frontend, kafka, payment, quote, shipping |
| 리소스 | `process.runtime.version` | 5066 | accounting, ad, checkout, email, fraud-detection, frontend, kafka, payment, quote, shipping |
| 리소스 | `service.criticality` | 13258 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, payment, product-catalog, quote, recommendation, shipping |
| 리소스 | `service.instance.id` | 2665 | accounting, ad, cart, fraud-detection, kafka, otelcol-contrib, payment, recommendation |
| 리소스 | `service.name` | 17979 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| 리소스 | `service.namespace` | 1779 | frontend-proxy |
| 리소스 | `service.version` | 801 | otelcol-contrib |
| 리소스 | `telemetry.auto.version` | 376 | recommendation |
| 리소스 | `telemetry.distro.name` | 592 | accounting, ad, fraud-detection, kafka, quote |
| 리소스 | `telemetry.distro.version` | 592 | accounting, ad, fraud-detection, kafka, quote |
| 리소스 | `telemetry.sdk.language` | 13258 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, payment, product-catalog, quote, recommendation, shipping |
| 리소스 | `telemetry.sdk.name` | 13258 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, payment, product-catalog, quote, recommendation, shipping |
| 리소스 | `telemetry.sdk.version` | 13258 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, payment, product-catalog, quote, recommendation, shipping |
| 리소스 | `user_agent.original` | 1649 | frontend-web |
| 리소스 | `zone_name` | 1779 | frontend-proxy |
| span 기본 | `endTimeUnixNano` | 12661 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| span 기본 | `kind` | 12661 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| span 기본 | `name` | 12661 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| span 기본 | `parentSpanId` | 10715 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping |
| span 기본 | `spanId` | 12661 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| span 기본 | `startTimeUnixNano` | 12661 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| span 기본 | `status.code` | 150 | currency, load-generator |
| span 기본 | `status.message` | 13 | load-generator |
| span 기본 | `traceId` | 12661 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, frontend-web, image-provider, load-generator, payment, product-catalog, quote, recommendation, shipping, telemetry-docs |
| span 속성 | `canceled` | 27 | frontend-proxy |
| span 속성 | `client.address` | 1388 | frontend, shipping |
| span 속성 | `code.file.path` | 50 | quote |
| span 속성 | `code.function` | 48 | ad |
| span 속성 | `code.function.name` | 50 | quote |
| span 속성 | `code.line.number` | 50 | quote |
| span 속성 | `code.namespace` | 48 | ad |
| span 속성 | `component` | 3450 | frontend-proxy |
| span 속성 | `db.namespace` | 15 | accounting |
| span 속성 | `db.npgsql.connection_id` | 15 | accounting |
| span 속성 | `db.npgsql.data_source` | 15 | accounting |
| span 속성 | `db.query.text` | 704 | accounting, product-catalog |
| span 속성 | `db.redis.database_index` | 343 | cart |
| span 속성 | `db.statement` | 343 | cart |
| span 속성 | `db.system` | 343 | cart |
| span 속성 | `db.system.name` | 705 | accounting, product-catalog |
| span 속성 | `demo.ad.category` | 77 | ad, load-generator |
| span 속성 | `demo.ad.context_keys` | 51 | ad |
| span 속성 | `demo.ad.context_keys.count` | 51 | ad |
| span 속성 | `demo.ad.count` | 106 | ad |
| span 속성 | `demo.ad.request_type` | 51 | ad |
| span 속성 | `demo.ad.response_type` | 51 | ad |
| span 속성 | `demo.cart.items.count` | 187 | cart, checkout, load-generator |
| span 속성 | `demo.exchange.from` | 110 | currency |
| span 속성 | `demo.exchange.to` | 110 | currency |
| span 속성 | `demo.order.amount` | 14 | checkout |
| span 속성 | `demo.order.id` | 42 | checkout, email |
| span 속성 | `demo.order.items.count` | 28 | checkout |
| span 속성 | `demo.payment.amount` | 14 | payment |
| span 속성 | `demo.payment.card_cvv` | 14 | payment |
| span 속성 | `demo.payment.card_number` | 14 | payment |
| span 속성 | `demo.payment.card_type` | 14 | payment |
| span 속성 | `demo.payment.card_valid` | 14 | payment |
| span 속성 | `demo.payment.charged` | 14 | payment |
| span 속성 | `demo.product.count` | 215 | product-catalog, recommendation |
| span 속성 | `demo.product.filtered.count` | 95 | recommendation |
| span 속성 | `demo.product.filtered.list` | 95 | recommendation |
| span 속성 | `demo.product.id` | 748 | cart, load-generator, product-catalog |
| span 속성 | `demo.product.name` | 570 | product-catalog |
| span 속성 | `demo.product.quantity` | 89 | cart, load-generator |
| span 속성 | `demo.product.recommended.count` | 95 | recommendation |
| span 속성 | `demo.shipping.amount` | 28 | checkout |
| span 속성 | `demo.shipping.cost.total` | 25 | shipping |
| span 속성 | `demo.shipping.quote.cost.total` | 25 | quote |
| span 속성 | `demo.shipping.quote.items_count` | 25 | quote |
| span 속성 | `demo.shipping.tracking.id` | 14 | checkout |
| span 속성 | `demo.synthetic_request` | 495 | frontend-web |
| span 속성 | `demo.user_context.loyalty_level` | 14 | payment |
| span 속성 | `demo.user_context.selected_currency` | 14 | checkout |
| span 속성 | `downstream_cluster` | 1725 | frontend-proxy |
| span 속성 | `enduser.id` | 1671 | ad, frontend-web |
| span 속성 | `event_type` | 33 | frontend-web |
| span 속성 | `exemplar` | 35 | ad |
| span 속성 | `gen_ai.input.messages` | 13 | load-generator |
| span 속성 | `grpc.method` | 229 | cart |
| span 속성 | `grpc.status_code` | 229 | cart |
| span 속성 | `guid:x-request-id` | 1725 | frontend-proxy |
| span 속성 | `http.flavor` | 492 | image-provider, telemetry-docs |
| span 속성 | `http.method` | 3045 | frontend, frontend-proxy, image-provider, load-generator, telemetry-docs |
| span 속성 | `http.protocol` | 3450 | frontend-proxy |
| span 속성 | `http.request.body.size` | 64 | quote, shipping |
| span 속성 | `http.request.method` | 2253 | cart, checkout, email, frontend, frontend-web, quote, shipping |
| span 속성 | `http.request.method_original` | 11 | frontend |
| span 속성 | `http.request_content_length` | 492 | image-provider, telemetry-docs |
| span 속성 | `http.response.body.size` | 25 | quote |
| span 속성 | `http.response.status_code` | 2253 | cart, checkout, email, frontend, frontend-web, quote, shipping |
| span 속성 | `http.response_content_length` | 1588 | frontend-web, image-provider, telemetry-docs |
| span 속성 | `http.response_content_length_uncompressed` | 559 | frontend-web |
| span 속성 | `http.route` | 1480 | cart, email, frontend, image-provider, quote, shipping |
| span 속성 | `http.scheme` | 492 | image-provider, telemetry-docs |
| span 속성 | `http.status_code` | 5254 | frontend, frontend-proxy, image-provider, load-generator, telemetry-docs |
| span 속성 | `http.target` | 1033 | frontend, image-provider, telemetry-docs |
| span 속성 | `http.url` | 2012 | frontend-proxy, load-generator |
| span 속성 | `http.user_agent` | 492 | image-provider, telemetry-docs |
| span 속성 | `kafka.record.queue_time_ms` | 14 | fraud-detection |
| span 속성 | `messaging.batch.message_count` | 14 | fraud-detection |
| span 속성 | `messaging.client_id` | 42 | accounting, fraud-detection |
| span 속성 | `messaging.destination.name` | 56 | accounting, checkout, fraud-detection |
| span 속성 | `messaging.destination.partition.id` | 14 | fraud-detection |
| span 속성 | `messaging.kafka.consumer.group` | 42 | accounting, fraud-detection |
| span 속성 | `messaging.kafka.destination.partition` | 28 | accounting, checkout |
| span 속성 | `messaging.kafka.message.offset` | 42 | accounting, checkout, fraud-detection |
| span 속성 | `messaging.kafka.producer.duration_ms` | 14 | checkout |
| span 속성 | `messaging.kafka.producer.success` | 14 | checkout |
| span 속성 | `messaging.message.body.size` | 14 | fraud-detection |
| span 속성 | `messaging.operation` | 56 | accounting, checkout, fraud-detection |
| span 속성 | `messaging.system` | 56 | accounting, checkout, fraud-detection |
| span 속성 | `net.host.name` | 492 | image-provider, telemetry-docs |
| span 속성 | `net.host.port` | 492 | image-provider, telemetry-docs |
| span 속성 | `net.peer.ip` | 95 | recommendation |
| span 속성 | `net.peer.port` | 95 | recommendation |
| span 속성 | `net.sock.peer.addr` | 492 | image-provider, telemetry-docs |
| span 속성 | `net.sock.peer.port` | 492 | image-provider, telemetry-docs |
| span 속성 | `network.local.address` | 11 | frontend |
| span 속성 | `network.local.port` | 11 | frontend |
| span 속성 | `network.peer.address` | 1422 | ad, frontend |
| span 속성 | `network.peer.port` | 1411 | ad, frontend |
| span 속성 | `network.protocol.version` | 1708 | cart, checkout, frontend, quote, shipping |
| span 속성 | `network.transport` | 25 | checkout, frontend |
| span 속성 | `network.type` | 51 | ad |
| span 속성 | `next.route` | 629 | frontend |
| span 속성 | `next.rsc` | 541 | frontend |
| span 속성 | `next.span_category` | 1126 | frontend |
| span 속성 | `next.span_name` | 1126 | frontend |
| span 속성 | `next.span_type` | 1126 | frontend |
| span 속성 | `node_id` | 1725 | frontend-proxy |
| span 속성 | `peer.address` | 3450 | frontend-proxy |
| span 속성 | `peer.ipv4` | 11 | frontend |
| span 속성 | `peer.service` | 14 | checkout |
| span 속성 | `request_size` | 1725 | frontend-proxy |
| span 속성 | `response_flags` | 3450 | frontend-proxy |
| span 속성 | `response_size` | 1725 | frontend-proxy |
| span 속성 | `rpc.grpc.status_code` | 1274 | ad, frontend, payment, recommendation |
| span 속성 | `rpc.method` | 2245 | ad, cart, checkout, currency, frontend, payment, product-catalog, recommendation |
| span 속성 | `rpc.response.status_code` | 971 | cart, checkout, currency, product-catalog |
| span 속성 | `rpc.service` | 1274 | ad, frontend, payment, recommendation |
| span 속성 | `rpc.system` | 1274 | ad, frontend, payment, recommendation |
| span 속성 | `rpc.system.name` | 971 | cart, checkout, currency, product-catalog |
| span 속성 | `rpc.user_agent` | 95 | recommendation |
| span 속성 | `server.address` | 5216 | accounting, ad, cart, checkout, email, frontend, frontend-web, product-catalog, quote, shipping |
| span 속성 | `server.port` | 4497 | ad, cart, checkout, frontend, frontend-web, product-catalog, quote, shipping |
| span 속성 | `session.id` | 1700 | ad, frontend-web |
| span 속성 | `sinatra.template_name` | 28 | email |
| span 속성 | `target_element` | 33 | frontend-web |
| span 속성 | `target_xpath` | 33 | frontend-web |
| span 속성 | `thread.id` | 134 | ad, fraud-detection |
| span 속성 | `thread.name` | 134 | ad, fraud-detection |
| span 속성 | `upstream_address` | 1725 | frontend-proxy |
| span 속성 | `upstream_cluster` | 3450 | frontend-proxy |
| span 속성 | `upstream_cluster.name` | 3450 | frontend-proxy |
| span 속성 | `url.full` | 1776 | cart, checkout, frontend, frontend-web, quote, shipping |
| span 속성 | `url.path` | 1667 | cart, email, frontend, quote, shipping |
| span 속성 | `url.query` | 360 | frontend |
| span 속성 | `url.scheme` | 1667 | cart, email, frontend, quote, shipping |
| span 속성 | `user.id` | 296 | cart, checkout, load-generator |
| span 속성 | `user_agent` | 1725 | frontend-proxy |
| span 속성 | `user_agent.original` | 1979 | cart, email, frontend, frontend-web, load-generator, quote, shipping |
| span 속성 | `user_agent.synthetic.type` | 28 | checkout, payment |
| span 속성 | `zone` | 1725 | frontend-proxy |
| span 이벤트 이름 | `Calculating quote` | 25 | quote |
| span 이벤트 이름 | `Conversion successful, response sent back` | 110 | currency |
| span 이벤트 이름 | `Currencies fetched, response sent back` | 27 | currency |
| span 이벤트 이름 | `Empty cart` | 14 | cart |
| span 이벤트 이름 | `Enqueued` | 343 | cart |
| span 이벤트 이름 | `Fetch cart` | 165 | cart |
| span 이벤트 이름 | `Processing currency conversion request` | 110 | currency |
| span 이벤트 이름 | `Processing supported currencies request` | 27 | currency |
| span 이벤트 이름 | `Product Found` | 570 | product-catalog |
| span 이벤트 이름 | `Quote calculated, returning its value` | 25 | quote |
| span 이벤트 이름 | `Quote processed, response sent back` | 25 | quote |
| span 이벤트 이름 | `Received get quote request, processing it` | 25 | quote |
| span 이벤트 이름 | `ResponseReceived` | 343 | cart |
| span 이벤트 이름 | `Sent` | 343 | cart |
| span 이벤트 이름 | `charged` | 14 | checkout |
| span 이벤트 이름 | `connectEnd` | 1566 | frontend-web |
| span 이벤트 이름 | `connectStart` | 1566 | frontend-web |
| span 이벤트 이름 | `domComplete` | 25 | frontend-web |
| span 이벤트 이름 | `domContentLoadedEventEnd` | 25 | frontend-web |
| span 이벤트 이름 | `domContentLoadedEventStart` | 25 | frontend-web |
| span 이벤트 이름 | `domInteractive` | 25 | frontend-web |
| span 이벤트 이름 | `domainLookupEnd` | 1566 | frontend-web |
| span 이벤트 이름 | `domainLookupStart` | 1566 | frontend-web |
| span 이벤트 이름 | `exception` | 13 | load-generator |
| span 이벤트 이름 | `fetchStart` | 1613 | frontend-web |
| span 이벤트 이름 | `firstContentfulPaint` | 25 | frontend-web |
| span 이벤트 이름 | `firstPaint` | 25 | frontend-web |
| span 이벤트 이름 | `loadEventEnd` | 25 | frontend-web |
| span 이벤트 이름 | `loadEventStart` | 25 | frontend-web |
| span 이벤트 이름 | `message` | 102 | ad |
| span 이벤트 이름 | `prepared` | 14 | checkout |
| span 이벤트 이름 | `received-first-response` | 14 | accounting |
| span 이벤트 이름 | `requestStart` | 1566 | frontend-web |
| span 이벤트 이름 | `responseEnd` | 1588 | frontend-web |
| span 이벤트 이름 | `responseStart` | 1566 | frontend-web |
| span 이벤트 이름 | `secureConnectionStart` | 48 | frontend-web |
| span 이벤트 이름 | `shipped` | 14 | checkout |
| span 이벤트 이름 | `shipping.quote.received` | 25 | shipping |
| span 이벤트 속성 | `demo.payment.transaction.id` | 14 | checkout |
| span 이벤트 속성 | `demo.shipping.cost.total` | 25 | shipping |
| span 이벤트 속성 | `demo.shipping.quote.cost.total` | 25 | quote |
| span 이벤트 속성 | `demo.shipping.tracking.id` | 14 | checkout |
| span 이벤트 속성 | `exception.escaped` | 13 | load-generator |
| span 이벤트 속성 | `exception.message` | 13 | load-generator |
| span 이벤트 속성 | `exception.stacktrace` | 13 | load-generator |
| span 이벤트 속성 | `exception.type` | 13 | load-generator |
| span 이벤트 속성 | `message.id` | 102 | ad |
| span 이벤트 속성 | `message.type` | 102 | ad |
| log 기본 | `body` | 5318 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping |
| log 기본 | `severityNumber` | 3525 | accounting, ad, cart, checkout, currency, fraud-detection, frontend, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping |
| log 기본 | `severityText` | 3539 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation, shipping |
| log 기본 | `spanId` | 4179 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, load-generator, payment, product-catalog, quote, recommendation, shipping |
| log 기본 | `timeUnixNano` | 5122 | accounting, ad, cart, checkout, email, fraud-detection, frontend, frontend-proxy, kafka, load-generator, otelcol-contrib, payment, product-catalog, quote, recommendation |
| log 기본 | `traceId` | 4179 | accounting, ad, cart, checkout, currency, email, fraud-detection, frontend, frontend-proxy, load-generator, payment, product-catalog, quote, recommendation, shipping |
| log 속성 | `@OrderResult` | 14 | accounting |
| log 속성 | `amount` | 14 | payment |
| log 속성 | `cardType` | 14 | payment |
| log 속성 | `cents` | 25 | shipping |
| log 속성 | `code.file.path` | 337 | load-generator, recommendation |
| log 속성 | `code.function.name` | 337 | load-generator, recommendation |
| log 속성 | `code.line.number` | 337 | load-generator, recommendation |
| log 속성 | `context` | 25 | quote |
| log 속성 | `context.total` | 25 | quote |
| log 속성 | `currency.from` | 106 | currency |
| log 속성 | `currency.to` | 106 | currency |
| log 속성 | `data points` | 203 | otelcol-contrib |
| log 속성 | `demo.order.amount` | 14 | checkout |
| log 속성 | `demo.order.id` | 42 | checkout, email, frontend |
| log 속성 | `demo.order.items.count` | 14 | checkout |
| log 속성 | `demo.product.id` | 554 | product-catalog |
| log 속성 | `demo.product.name` | 554 | product-catalog |
| log 속성 | `demo.shipping.amount` | 14 | checkout |
| log 속성 | `demo.shipping.tracking.id` | 14 | checkout |
| log 속성 | `destination.address` | 1779 | frontend-proxy |
| log 속성 | `dollars` | 25 | shipping |
| log 속성 | `event.name` | 1779 | frontend-proxy |
| log 속성 | `http.request.method` | 489 | frontend |
| log 속성 | `http.response.status_code` | 489 | frontend |
| log 속성 | `lastFourDigits` | 14 | payment |
| log 속성 | `log records` | 300 | otelcol-contrib |
| log 속성 | `loyalty_level` | 14 | payment |
| log 속성 | `metrics` | 203 | otelcol-contrib |
| log 속성 | `otelServiceName` | 674 | load-generator, recommendation |
| log 속성 | `otelSpanID` | 674 | load-generator, recommendation |
| log 속성 | `otelTraceID` | 674 | load-generator, recommendation |
| log 속성 | `otelTraceSampled` | 674 | load-generator, recommendation |
| log 속성 | `productId` | 50 | cart |
| log 속성 | `products` | 116 | product-catalog |
| log 속성 | `quantity` | 50 | cart |
| log 속성 | `quote_service_addr` | 25 | shipping |
| log 속성 | `resource logs` | 300 | otelcol-contrib |
| log 속성 | `resource metrics` | 203 | otelcol-contrib |
| log 속성 | `resource spans` | 298 | otelcol-contrib |
| log 속성 | `server.address` | 1779 | frontend-proxy |
| log 속성 | `service.name` | 28 | payment |
| log 속성 | `source.address` | 1779 | frontend-proxy |
| log 속성 | `spans` | 298 | otelcol-contrib |
| log 속성 | `tracking_id` | 14 | shipping |
| log 속성 | `transactionId` | 14 | payment |
| log 속성 | `transaction_id` | 14 | checkout |
| log 속성 | `upstream.cluster` | 1779 | frontend-proxy |
| log 속성 | `upstream.host` | 1779 | frontend-proxy |
| log 속성 | `url.full` | 1779 | frontend-proxy |
| log 속성 | `url.path` | 2268 | frontend, frontend-proxy |
| log 속성 | `url.query` | 1779 | frontend-proxy |
| log 속성 | `url.template` | 1779 | frontend-proxy |
| log 속성 | `userId` | 229 | cart |
| log 속성 | `user_agent.original` | 1779 | frontend-proxy |
| log 속성 | `user_currency` | 14 | checkout |
| log 속성 | `user_id` | 14 | checkout |
