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
public class FileStorageController {

    private final Path rootLocation = Paths.get("upload-dir").toAbsolutePath().normalize();

    public FileStorageController() throws IOException {
        Files.createDirectories(rootLocation);
    }

    @GetMapping
    public ResponseEntity<?> get(HttpServletRequest request) throws IOException {
        Path requestedPath = resolvePath(request.getRequestURI());

        if (Files.notExists(requestedPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (Files.isDirectory(requestedPath)) {
            try (Stream<Path> entries = Files.list(requestedPath)) {
                List<String> items = entries
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());

                return ResponseEntity.ok(items);
            }
        }

        Resource fileResource = new UrlResource(requestedPath.toUri());
        String contentType = Files.probeContentType(requestedPath);
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + requestedPath.getFileName() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .contentLength(Files.size(requestedPath))
                .lastModified(Files.getLastModifiedTime(requestedPath).toMillis())
                .body(fileResource);
    }

    @PutMapping
    public ResponseEntity<?> put(
            @RequestHeader(value = "X-Copy-From", required = false) String copyFrom,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) throws IOException {

        Path targetPath = resolvePath(request.getRequestURI());

        if (copyFrom != null && !copyFrom.isBlank()) {
            Path sourcePath = resolvePath(copyFrom);

            if (Files.notExists(sourcePath) || Files.isDirectory(sourcePath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Source file not found");
            }

            boolean alreadyExists = Files.exists(targetPath);

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            return ResponseEntity.status(alreadyExists ? HttpStatus.OK : HttpStatus.CREATED).build();
        }

        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("No content provided");
        }

        boolean alreadyExists = Files.exists(targetPath);

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(
                targetPath,
                body,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        return ResponseEntity.status(alreadyExists ? HttpStatus.OK : HttpStatus.CREATED).build();
    }

    @RequestMapping(method = RequestMethod.HEAD)
    public ResponseEntity<?> head(HttpServletRequest request) throws IOException {
        Path requestedPath = resolvePath(request.getRequestURI());

        if (Files.notExists(requestedPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (Files.isDirectory(requestedPath)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("HEAD is only supported for files");
        }

        return ResponseEntity.ok()
                .contentLength(Files.size(requestedPath))
                .lastModified(Files.getLastModifiedTime(requestedPath).toMillis())
                .build();
    }

    @DeleteMapping
    public ResponseEntity<?> delete(HttpServletRequest request) throws IOException {
        Path requestedPath = resolvePath(request.getRequestURI());

        if (Files.notExists(requestedPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (Files.isDirectory(requestedPath)) {
            FileSystemUtils.deleteRecursively(requestedPath);
        } else {
            Files.delete(requestedPath);
        }

        return ResponseEntity.noContent().build();
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