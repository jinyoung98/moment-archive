package dev.jinyoung.archive.processing.thumb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * libvips CLI 구현. {@code vips thumbnail} 하위 명령을 서브프로세스로 호출 (ADR A19).
 *
 * {@code vipsthumbnail} 래퍼가 아니라 {@code vips thumbnail} 를 쓰는 이유: 출력 경로를
 * 인자로 명시 지정 가능 — 래퍼의 접미사/패턴 규칙을 피함.
 *
 * webp 로 출력, {@code strip} 로 메타 제거(용량↓), EXIF 회전 자동 적용(기본 동작).
 */
@Component
public class VipsThumbnailer implements Thumbnailer {

    private static final Logger log = LoggerFactory.getLogger(VipsThumbnailer.class);

    private final ThumbnailProperties props;

    public VipsThumbnailer(ThumbnailProperties props) {
        this.props = props;
    }

    @Override
    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(props.command(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;          // 실행 파일 없음 = 미설치
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void thumbnail(Path source, Path output) {
        // 출력 파일명에 붙는 [Q=..,strip] 는 libvips 의 저장 옵션 문법.
        String target = output.toString() + "[Q=" + props.quality() + ",strip]";
        List<String> command = List.of(
                props.command(), "thumbnail",
                source.toString(),
                target,
                String.valueOf(props.size()),
                "--size", "down");        // 원본보다 키우지 않음

        run(command, source);
    }

    private void run(List<String> command, Path source) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ThumbnailException("libvips 실행 실패: " + props.command(), e);
        }

        try {
            boolean finished = process.waitFor(props.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ThumbnailException("libvips 타임아웃(" + props.timeoutSeconds() + "s): " + source);
            }
            if (process.exitValue() != 0) {
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                throw new ThumbnailException("libvips 비정상 종료(" + process.exitValue() + "): " + out);
            }
        } catch (IOException e) {
            throw new ThumbnailException("libvips 출력 읽기 실패", e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ThumbnailException("libvips 대기 중 인터럽트", e);
        }
        log.debug("썸네일 생성: {} -> {}", source, command.get(3));
    }
}
