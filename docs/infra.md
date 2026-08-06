# 인프라 실행 환경

> 무엇을 어디서 돌리는가. **이 프로젝트는 로컬에 Docker 데몬이 없다.**
> 관련: [roadmap.md](./roadmap.md) §5 하네스 스택, [design.md](./design.md) §3 아키텍처

---

## 1. 원칙

**모든 컨테이너는 원격 서버의 Docker 데몬에서 돈다.** 개발 노트북에는 Docker Desktop도 Colima도 없다.

| 대상 | 어디서 | 어떻게 |
|---|---|---|
| 상시 스택 (Postgres, MinIO) | 서버 | `docker compose up -d` — 사람이 직접 |
| Testcontainers 일회용 컨테이너 | 서버 | 로컬 JVM이 `DOCKER_HOST`로 원격 데몬 조작 |
| 애플리케이션 (Spring Boot) | 로컬 | 서버의 Postgres·MinIO에 네트워크로 접속 |
| 프론트엔드 dev server | 로컬 | — |

> ⚠️ **에이전트에게**: `docker`, `docker compose`, `colima` 명령을 로컬에서 실행하지 않는다.
> 컨테이너 관련 작업이 필요하면 명령과 파일만 제시하고 사람이 서버에서 실행한다.

### 왜 이렇게 나눴나

노트북에 Docker를 두지 않겠다는 것은 환경 결정이지 설계 결정이 아니다. 대신 **그 대가를 문서에 남긴다** — §5의 제약들은 코드 작성 방식을 실제로 바꾼다. 특히 바인드 마운트를 쓸 수 없다는 점은 테스트를 짜기 전에 알아야 한다.

---

## 2. 서버 준비 — 상시 스택

전제: Ubuntu/Debian 계열, Docker 설치됨, SSH 접속 가능.

> **먼저 이 단계만 끝낸다.** 네트워크 노출(§3)은 그다음이다.
> 둘을 한 번에 하면 문제가 생겼을 때 "컨테이너가 안 뜬 것"인지 "네트워크가 안 뚫린 것"인지
> 구분이 안 된다. 서버 안에서 도는 것부터 확인하고 넘어간다.

### 2.1 파일 옮기기

**저장소를 통째로 clone 하지 않는다.** 서버에 필요한 것은 `docker-compose.yml` 과 `.env.example`
두 개뿐이고, 이 저장소에는 아직 원격이 없다.

노트북에서:

```bash
ssh <user>@<서버> 'mkdir -p ~/archive'
scp docker-compose.yml .env.example <user>@<서버>:~/archive/
```

### 2.2 Docker 권한 확인

서버에 접속해서:

```bash
ssh <user>@<서버>
docker ps
```

`permission denied` 가 나오면 현재 계정이 docker 그룹에 없는 것이다. 매번 `sudo` 를 붙이는
대신 그룹에 넣는다.

```bash
sudo usermod -aG docker $USER
```

**적용되려면 다시 로그인해야 한다.** `exit` 후 재접속하고 `docker ps` 를 다시 확인한다.

### 2.3 비밀번호 만들고 `.env` 채우기

```bash
cd ~/archive
cp .env.example .env
openssl rand -hex 24       # 두 번 실행해 Postgres·MinIO 용으로 각각 하나씩
```

> `-base64` 가 아니라 `-hex` 를 쓴다. base64 는 `+` `/` `=` 를 섞어 내는데,
> `.env` 파싱과 셸 인용에서 사고가 나기 쉽다. hex 는 영숫자뿐이라 그런 일이 없다.

`nano .env` 로 열어 세 값을 채운다. 나머지 기본값은 그대로 둔다.

| 키 | 값 |
|---|---|
| `POSTGRES_PASSWORD` | 생성한 문자열 |
| `MINIO_ROOT_USER` | 예: `archive-admin` (8자 이상) |
| `MINIO_ROOT_PASSWORD` | 생성한 문자열 |

`.env` 는 서버에만 두고 저장소에 넣지 않는다. `.gitignore` 에 등록되어 있다.

**포트가 겹칠 때**는 compose 파일을 고치지 말고 `.env` 의 `POSTGRES_PORT`·`MINIO_API_PORT`·
`MINIO_CONSOLE_PORT` 를 바꾼다. 컨테이너 내부 포트는 항상 그대로이므로 호스트 쪽만 비켜주면 된다.
이 서버는 이미 다른 Postgres 가 5432·5433 을 쓰고 있어 **`POSTGRES_PORT=5434`** 를 기본값으로 둔다.

### 2.4 기동

```bash
docker compose up -d
```

처음에는 이미지를 받느라 몇 분 걸린다.

### 2.5 확인

```bash
docker compose ps
```

`postgres` 와 `minio` 가 `running (healthy)` 여야 한다. `minio-init` 은 목록에 없거나
`exited (0)` 인데, **이게 정상이다** — 버킷을 만들고 스스로 끝나는 일회성 컨테이너다.

```bash
docker compose logs minio-init
```

`bucket ready: archive` 가 보이면 성공이다. 멱등하므로 `up` 을 다시 해도 안전하다.

DB 가 실제로 응답하는지도 본다.

```bash
docker compose exec postgres psql -U archive -d archive -c '\dt'
```

`Did not find any relations.` 가 나오면 정상이다. 테이블은 애플리케이션이 Flyway 로 만든다.

여기까지 되면 상시 스택은 완성이다. 버킷은 **하나**(`archive`)이고 원본·파생물은 접두사로
나눈다 — `originals/ab/cd/…`, `derivatives/…`. [design.md](./design.md) §5.3 의
`storage_key` 예시와 같은 구조다.

### 2.6 막혔을 때

| 증상 | 원인과 조치 |
|---|---|
| `permission denied ... docker.sock` | §2.2 의 그룹 추가 후 **재로그인**하지 않았다 |
| `port is already allocated` | 해당 포트를 다른 컨테이너·프로세스가 쓰고 있다. `sudo ss -lptn 'sport = :5434'` 로 확인하고 `.env` 의 `POSTGRES_PORT` 를 비어 있는 값으로 바꾼다 |
| `POSTGRES_PASSWORD ... is required` | `.env` 를 안 만들었거나 값이 비어 있다. `docker compose config` 로 치환 결과를 확인 |
| `minio` 가 `unhealthy` | `docker compose logs minio`. 대개 `MINIO_ROOT_PASSWORD` 가 8자 미만 |
| `minio-init` 이 실패 | `docker compose logs minio-init`. 자격 오타가 대부분. 고친 뒤 `docker compose up -d minio-init` 으로 재실행 |

멈추고 다시 시작하려면:

```bash
docker compose down          # 컨테이너만 내림. 데이터는 볼륨에 남는다
docker compose down -v       # 볼륨까지 삭제 — DB 와 업로드가 전부 사라진다. 주의
```

---

## 3. 로컬 → 서버 연결

### 3.1 권장: Twingate

**핵심 요건은 하나다 — 노트북이 서버의 임의의 높은 포트에 닿을 수 있어야 한다.** Testcontainers가 여는 포트는 실행할 때마다 바뀌므로, 포트를 하나씩 열어주는 방식으로는 감당이 안 된다.

Twingate는 Resource에 포트를 지정하지 않으면 기본이 전체 허용(`ALLOW_ALL`)이고 사설 IP·CIDR 로 Resource 를 정의할 수 있어서 이 요건을 그대로 만족한다.

**Twingate 콘솔에서**: 서버의 사설 IP(예: `10.10.0.5`)를 Resource 로 등록하고 **포트 제한을 걸지 않는다.** 굳이 좁히겠다면 최소한 아래는 열려 있어야 한다.

| 포트 | 용도 |
|---|---|
| `2375` | Docker 데몬 API |
| `5434` | Postgres (상시 스택). `.env` 의 `POSTGRES_PORT` 값 |
| `9000`, `9001` | MinIO API·콘솔 |
| `32768-60999` | **Testcontainers 가 매번 새로 여는 포트** |

노트북에 Twingate 클라이언트를 설치하고 해당 Resource 에 접근 권한을 준다. 아래에서 이 사설 IP 를 계속 쓴다.

**서버에서** Docker 데몬을 사설 IP 에만 바인딩한다:

```bash
IP=10.10.0.5                                          # 실제 서버 사설 IP 로 바꾼다
ORIG=$(systemctl cat docker | grep -m1 '^ExecStart=')
sudo mkdir -p /etc/systemd/system/docker.service.d
printf '[Service]\nExecStart=\n%s -H tcp://%s:2375\n' "$ORIG" "$IP" \
  | sudo tee /etc/systemd/system/docker.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart docker
```

> ⚠️ **원래 `ExecStart` 를 읽어서 뒤에 덧붙이는 것이 핵심이다.**
> `/usr/bin/dockerd -H fd://` 로 통째로 덮어쓰면 배포판이 넣어둔 `--containerd=…` 같은
> 플래그가 사라져 데몬이 뜨지 않거나 이상하게 동작한다. systemd 에서 `ExecStart=` 빈 줄은
> "기존 값을 지운다"는 뜻이고, 그다음 줄이 새 값이 된다.

확인:

```bash
sudo ss -lptn | grep 2375
```

> 🔴 **`tcp://0.0.0.0:2375` 로 열지 말 것.** 인증 없는 Docker 데몬 포트는 서버 루트 권한과 같다. 스캐너가 몇 시간 안에 찾아낸다. 반드시 사설 IP 에만 바인딩한다.

상시 스택 포트도 같은 주소에서 닿아야 한다. `docker-compose.yml` 은 `127.0.0.1` 에 묶여 있으므로 바인딩 주소를 바꾼다.

```yaml
# docker-compose.yml — Twingate 사용 시
ports:
  - "10.10.0.5:${POSTGRES_PORT:-5432}:5432"
```

### 3.2 대안: SSH 터널

사설망을 못 쓸 때. Docker 소켓을 로컬 포트로 끌어온다.

```bash
ssh -N -L 12375:/var/run/docker.sock <user>@<server>
```

상시 스택도 같이:

```bash
ssh -N -L 12375:/var/run/docker.sock -L 5434:localhost:5434 -L 9000:localhost:9000 <user>@<server>
```

이 방식은 **상시 스택에는 충분하지만 Testcontainers에는 부족하다.** 터널은 미리 정한 포트만 넘기는데, Testcontainers가 여는 포트는 실행할 때마다 달라지기 때문이다. Testcontainers까지 쓰려면 서버 방화벽에서 노트북 IP에 한해 임시 포트 범위를 열어야 한다.

```bash
sudo ufw allow from <노트북-공인IP> to any port 32768:60999 proto tcp
```

노트북 IP가 바뀔 때마다 갱신해야 한다. **그래서 §3.1을 권장한다.**

### 3.3 환경 변수

로컬 셸에 설정한다. `~/.zshrc`나 `direnv`에 넣어두면 편하다.

```bash
# Twingate 방식 (§3.1)
export DOCKER_HOST=tcp://10.10.0.5:2375
export TESTCONTAINERS_HOST_OVERRIDE=10.10.0.5

# SSH 터널 방식 (§3.2)
export DOCKER_HOST=tcp://localhost:12375
export TESTCONTAINERS_HOST_OVERRIDE=10.10.0.5
```

두 변수의 역할이 다르다. 헷갈리면 원인 모를 타임아웃을 만난다.

| 변수 | 역할 |
|---|---|
| `DOCKER_HOST` | **컨테이너를 만들 곳.** Testcontainers가 데몬 API를 호출하는 주소 |
| `TESTCONTAINERS_HOST_OVERRIDE` | **컨테이너에 접속할 곳.** 매핑된 포트로 JDBC·S3 연결을 걸 주소 |

`TESTCONTAINERS_HOST_OVERRIDE`가 없으면 Testcontainers는 컨테이너가 `localhost`에 있다고 가정한다. 그러면 컨테이너는 서버에 떠 있는데 JDBC 연결은 노트북의 매핑 포트를 두드리다 실패한다.

> 참고: 상시 스택의 포트 충돌(§2.3)은 **Testcontainers 와 무관하다.** Testcontainers 는 매번
> 비어 있는 포트를 스스로 골라 매핑하므로 고정 포트를 쓰지 않는다.

연결 확인:

```bash
docker info --format '{{.Name}} / {{.ServerVersion}}'
```

서버 호스트명이 나오면 성공이다.

---

## 4. 이미지 태그 고정

`docker-compose.yml`의 `minio/minio:latest`는 **임시**다. 로드맵 §5의 결정론 체크리스트와 어긋나므로, 서버에서 첫 `pull` 이후 고정한다.

```bash
docker compose pull
docker compose images --format json
```

출력된 digest를 `image: minio/minio@sha256:…` 형태로 박고, 커밋 메시지에 고정 시점을 남긴다. `minio/mc`도 같이 처리한다.

---

## 5. 원격 데몬의 제약 — 코드 작성 전에 알아야 할 것

데몬이 원격이라 로컬 Docker와 동작이 다른 지점들이다. 모르고 짜면 W2~W4에서 되돌아와야 한다.

| 제약 | 내용 | 대응 |
|---|---|---|
| **바인드 마운트 불가** | `withFileSystemBind`, `Volume` 매핑의 경로는 **서버 기준**으로 해석된다. 노트북의 파일은 컨테이너에 보이지 않는다 | `withCopyFileToContainer(MountableFile…)` 로 복사. 파일을 API로 밀어넣는 방식이라 원격에서도 동작한다 |
| **골든셋 파일** | `golden/media/`는 노트북에만 있다 | 컨테이너에 마운트하지 않는다. JVM이 직접 읽어 MinIO에 업로드하는 경로로 설계한다 (실제 파이프라인과도 같은 모양이라 오히려 낫다) |
| **Ryuk 정리 컨테이너** | Testcontainers가 띄우는 정리용 컨테이너에도 JVM이 접속해야 한다 | §3.1의 사설망이면 자동 해결. 안 되면 `TESTCONTAINERS_RYUK_DISABLED=true` 후 수동 정리 — 단 컨테이너가 새는지 주기적으로 확인할 것 |
| **테스트 지연** | 모든 JDBC·S3 호출이 네트워크를 탄다. 로컬 데몬 대비 느리다 | W1에서 `./gradlew test` 실측치를 [progress.md](./progress.md) 측정 기록에 남긴다. 로드맵 §6 에이전트 루프의 반복 속도가 여기에 직접 묶인다 |
| **첫 실행 느림** | 이미지 pull이 서버에서 일어난다 | 1회성. 서버에서 미리 `docker pull postgres:16-alpine` 해두면 첫 테스트가 빠르다 |
| **서버 재부팅** | 터널이 끊기고 `DOCKER_HOST`가 죽는다 | `docker info` 실패 시 터널·데몬부터 확인 |

> 📌 **성능 기준선을 여기서 재면 안 된다.** 로드맵 §7의 "사진 70장 처리 시간" 같은 숫자는 네트워크 왕복이 섞이면 의미가 흐려진다. 기준선 측정은 서버에서 직접 실행하거나, 측정 조건을 숫자 옆에 반드시 적는다.

---

## 6. 체크리스트

서버 쪽 (사람이 실행):

- [ ] **§2 전체** — 파일 복사, docker 그룹, `.env` 작성, `up -d`, `minio-init` 정상 종료 확인
- [ ] Twingate 콘솔에 서버 사설 IP 를 Resource 로 등록 (**포트 제한 없이**)
- [ ] dockerd 를 사설 IP 에 바인딩, `systemctl restart docker`
- [ ] compose 포트 바인딩을 사설 IP 로 변경 후 재기동
- [ ] `docker pull postgres:16-alpine` 선행
- [ ] 이미지 digest 고정 (§4)

로컬 쪽:

- [ ] Twingate 클라이언트 연결 및 해당 Resource 접근 권한 확인
- [ ] `DOCKER_HOST`, `TESTCONTAINERS_HOST_OVERRIDE` 설정
- [ ] `docker info`가 서버를 가리키는지 확인
- [ ] `application-local.yml`에 서버 Postgres·MinIO 주소 기입 (커밋 안 됨)
