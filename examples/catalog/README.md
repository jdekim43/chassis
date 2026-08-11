# catalog 예제

설정, CLI, Worker, health, HTTP 서버를 한 앱으로 조립한 예제다. 상품 이름이 한 줄에 하나씩 적힌 파일을 읽어 메모리에 들고 있다가, `list`로 출력하거나 `serve`로 HTTP에 답하면서 파일을 주기적으로 다시 읽는다.

## 구성

| 파일 | 내용 |
| --- | --- |
| `Config.kt` | 설정 클래스. `[catalog]`, `[health]`, `[server]` 섹션을 읽고 `source`가 비어 있지 않은지, 주기가 양수인지, 포트가 범위 안인지 검증한다 |
| `Catalog.kt` | 파일을 읽는 `ProductSource`와 메모리에 보관하는 `CatalogStore` |
| `Workers.kt` | 파일을 다시 읽는 `CatalogRefresh`, readiness가 바뀔 때 로그를 남기는 `HealthMonitor`. `serve`에서만 돈다 |
| `Commands.kt` | `list`와 `serve` 명령. HTTP 서버는 `serve`가 연다 |
| `CatalogModule.kt` | 위의 것들을 등록하는 Koin 모듈. 서버 정의와 `GET /products`, health 경로도 여기 있다 |
| `Main.kt` | 진입점 |

## 설정

```toml
[catalog]
source = "config/products.txt"
refresh-interval = "30s"

[health]
interval = "10s"

[server]
host = "127.0.0.1"
port = 8080
```

- 주기는 `"30s"`, `"500ms"`, `"1m 30s"`처럼 쓴다. ISO 표기(`"PT30S"`)도 읽는다.
- `source`의 상대 경로는 설정 파일의 위치가 아니라 실행한 위치를 기준으로 한다.
- `[server]`는 생략하면 `127.0.0.1:8080`이다. `port = 0`이면 빈 포트를 배정받는다. `list`는 이 포트를 열지 않는다.
- 키가 빠지거나 틀리면 그 키를 짚는다: `Error: Configuration decoding failed (source=defaults, section=catalog.source): Required value is missing`.

## 실행

저장소 루트에서 실행한다. 경로는 `examples/catalog` 기준이다.

```bash
./gradlew -q :examples:catalog:run --args="--config config/application.toml list"
```

```bash
./gradlew -q :examples:catalog:run --args="--config config/application.toml --log stdout serve"
```

`serve`는 서버를 열고 `Serving on http://127.0.0.1:8080. Press Ctrl+C to stop.`을 출력한다. 다른 터미널에서 확인한다.

```bash
curl -s http://127.0.0.1:8080/products
```

```bash
curl -s -i http://127.0.0.1:8080/health/ready
```

`/health/ready`와 `/health/live`는 정상이면 200, 아니면 503이고, 본문에 점검마다 한 줄이 있다. 그 밖의 진행 상황은 로그에만 남으므로 보려면 `--log`를 준다. Ktor의 로그도 같은 곳에 같은 형식으로 남는다. 도는 동안 `config/products.txt`를 고치면 `Catalog refreshed`가, 지우면 `Catalog source unreadable`과 `Readiness: unhealthy`가 로그에 남는다.

## 종료

Ctrl+C(SIGINT)나 SIGTERM을 받으면 Worker를 멈추고, 진행 중인 요청을 기다린 뒤 서버를 닫고, 종료 절차를 마친 뒤 끝난다. 로그에 `stop requested: JVM shutdown`, `Ktor server stopped`, `STOPPING -> STOPPED`가 남는다. `./gradlew run`으로 띄웠을 때도 같다(터미널의 Ctrl+C가 아니라 Gradle 프로세스에 SIGINT를 보내 확인했다). Gradle 없이 실행하려면 스크립트를 만들어 쓴다.

```bash
./gradlew :examples:catalog:installDist
```

```bash
cd examples/catalog && build/install/catalog/bin/catalog --config config/application.toml --log stdout serve
```

## 테스트

```bash
./gradlew :examples:catalog:test
```
