package store;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/**")
public class StoreController {

    private final Path rootLocation = Paths.get("loaded_data").toAbsolutePath().normalize();

    public StoreController() throws IOException {
        Files.createDirectories(rootLocation);
    }

    @GetMapping
    public ResponseEntity<?> getMethod(HttpServletRequest request) throws IOException {
        Path path = resolvePath(request.getRequestURI());

        if (Files.notExists(path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (Files.isDirectory(path)) {
            return ResponseEntity.ok(readDirectory(path));
        }

        return buildFileResponse(path);
    }

    @PutMapping
    public ResponseEntity<?> putMethod(
            @RequestHeader(value = "X-Copy-From", required = false) String copyFrom,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) throws IOException {

        Path targetPath = resolvePath(request.getRequestURI());

        if (copyFrom != null && !copyFrom.isBlank()) {
            Path sourcePath = resolvePath(copyFrom);

            if (sourceFileMissingOrDirectory(sourcePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Source file not found");
            }

            boolean existedBefore = Files.exists(targetPath);
            createParentDirectories(targetPath);
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            return putResult(existedBefore);
        }

        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("No content");
        }

        boolean existedBefore = Files.exists(targetPath);
        createParentDirectories(targetPath);

        Files.write(
                targetPath,
                body,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        return putResult(existedBefore);
    }

    @RequestMapping(method = RequestMethod.HEAD)
    public ResponseEntity<?> headMethod(HttpServletRequest request) throws IOException {
        Path path = resolvePath(request.getRequestURI());

        if (Files.notExists(path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (Files.isDirectory(path)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("HEAD is only supported for files");
        }

        return ResponseEntity.ok()
                .contentLength(Files.size(path))
                .lastModified(Files.getLastModifiedTime(path).toMillis())
                .build();
    }

    @DeleteMapping
    public ResponseEntity<?> delete(HttpServletRequest request) throws IOException {
        Path path = resolvePath(request.getRequestURI());

        if (Files.notExists(path)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        deletePath(path);
        return ResponseEntity.noContent().build();
    }

    private List<String> readDirectory(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private ResponseEntity<Resource> buildFileResponse(Path path) throws IOException {
        Resource resource = new UrlResource(path.toUri());
        String contentType = Files.probeContentType(path);

        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + path.getFileName() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .contentLength(Files.size(path))
                .lastModified(Files.getLastModifiedTime(path).toMillis())
                .body(resource);
    }

    private boolean sourceFileMissingOrDirectory(Path path) throws IOException {
        return Files.notExists(path) || Files.isDirectory(path);
    }

    private void createParentDirectories(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private ResponseEntity<Void> putResult(boolean existedBefore) {
        return ResponseEntity.status(existedBefore ? HttpStatus.OK : HttpStatus.CREATED).build();
    }

    private void deletePath(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            FileSystemUtils.deleteRecursively(path);
        } else {
            Files.delete(path);
        }
    }

    private Path resolvePath(String uri) {
        String decodedUri = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        String relativePath = decodedUri.startsWith("/") ? decodedUri.substring(1) : decodedUri;
        Path resolvedPath = rootLocation.resolve(relativePath).normalize();

        if (!resolvedPath.startsWith(rootLocation)) {
            throw new IllegalArgumentException("Invalid path");
        }

        return resolvedPath;
    }
}