package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.vo.FileInfoVO;
import com.easyshell.server.service.FileProxyService;
import com.easyshell.server.websocket.AgentWebSocketHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProxyServiceImpl implements FileProxyService {

    private final AgentWebSocketHandler agentHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();
    // For download: accumulate chunks
    private final Map<String, BlockingQueue<String>> downloadQueues = new ConcurrentHashMap<>();

    private static final long LIST_TIMEOUT_SECONDS = 10;
    private static final long DOWNLOAD_TIMEOUT_SECONDS = 86400; // 24h — effectively unlimited
    private static final long UPLOAD_TIMEOUT_SECONDS = 86400; // 24h — effectively unlimited
    private static final long OPERATION_TIMEOUT_SECONDS = 10;
    private static final int CHUNK_SIZE = 256 * 1024; // 256KB
    private static final int MAX_CHUNK_RETRIES = 3;

    @PostConstruct
    public void init() {
        agentHandler.setFileProxyService(this);
    }

    @Override
    public List<FileInfoVO> listFiles(String agentId, String path) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            Map<String, Object> msg = Map.of(
                "type", "file_list",
                "requestId", requestId,
                "path", path
            );
            sendToAgent(agentId, msg);

            String response = future.get(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return parseFileListResponse(response);
        } catch (TimeoutException e) {
            throw new BusinessException(504, "File operation timed out");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("File operation failed: " + e.getMessage());
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    @Override
    public ResponseEntity<StreamingResponseBody> downloadFile(String agentId, String path) {
        String requestId = UUID.randomUUID().toString();
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        downloadQueues.put(requestId, queue);

        try {
            Map<String, Object> msg = Map.of(
                "type", "file_download",
                "requestId", requestId,
                "path", path
            );
            sendToAgent(agentId, msg);

            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

            // Wait for the first chunk to get TotalSize for Content-Length header
            String firstChunkJson = queue.poll(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (firstChunkJson == null) {
                throw new BusinessException(504, "Download timed out waiting for first chunk");
            }

            JsonNode firstNode = objectMapper.readTree(firstChunkJson);
            if (firstNode.has("success") && !firstNode.get("success").asBoolean()) {
                String error = firstNode.has("error") ? firstNode.get("error").asText() : "Download failed";
                throw new BusinessException(error);
            }

            long totalSize = firstNode.has("totalSize") ? firstNode.get("totalSize").asLong() : -1;

            StreamingResponseBody responseBody = outputStream -> {
                try {
                    // Process first chunk that we already fetched
                    String data = firstNode.has("data") ? firstNode.get("data").asText() : "";
                    if (!data.isEmpty()) {
                        byte[] decoded = Base64.getDecoder().decode(data);
                        outputStream.write(decoded);
                        outputStream.flush();
                    }

                    boolean isFinal = firstNode.has("final") && firstNode.get("final").asBoolean();
                    if (isFinal) {
                        return;
                    }

                    // Continue processing remaining chunks
                    while (true) {
                        String chunkJson = queue.poll(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (chunkJson == null) {
                            throw new RuntimeException("Download timed out");
                        }

                        JsonNode node = objectMapper.readTree(chunkJson);

                        // Check for error response
                        if (node.has("success") && !node.get("success").asBoolean()) {
                            String error = node.has("error") ? node.get("error").asText() : "Download failed";
                            throw new RuntimeException(error);
                        }

                        String chunkData = node.has("data") ? node.get("data").asText() : "";
                        if (!chunkData.isEmpty()) {
                            byte[] decoded = Base64.getDecoder().decode(chunkData);
                            outputStream.write(decoded);
                            outputStream.flush();
                        }

                        boolean chunkFinal = node.has("final") && node.get("final").asBoolean();
                        if (chunkFinal) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Download interrupted", e);
                } finally {
                    downloadQueues.remove(requestId);
                }
            };

            var responseBuilder = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encodedFileName);

            if (totalSize > 0) {
                responseBuilder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(totalSize));
            }

            return responseBuilder.body(responseBody);
        } catch (BusinessException e) {
            downloadQueues.remove(requestId);
            throw e;
        } catch (InterruptedException e) {
            downloadQueues.remove(requestId);
            Thread.currentThread().interrupt();
            throw new BusinessException("Download interrupted");
        } catch (Exception e) {
            downloadQueues.remove(requestId);
            throw new BusinessException("Download failed: " + e.getMessage());
        }
    }

    @Override
    public void uploadFile(String agentId, String path, MultipartFile file) {
        String requestId = UUID.randomUUID().toString();

        try {
            byte[] fileBytes = file.getBytes();

            // Send upload start
            CompletableFuture<String> startFuture = new CompletableFuture<>();
            pendingRequests.put(requestId, startFuture);

            String destPath = path.endsWith("/") ? path + file.getOriginalFilename() : path + "/" + file.getOriginalFilename();

            Map<String, Object> startMsg = new HashMap<>();
            startMsg.put("type", "file_upload_start");
            startMsg.put("requestId", requestId);
            startMsg.put("path", destPath);
            startMsg.put("size", (long) fileBytes.length);
            sendToAgent(agentId, startMsg);

            // Wait for start acknowledgment
            String startResponse = startFuture.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            pendingRequests.remove(requestId);
            checkResponseSuccess(startResponse);

            // Send chunks
            int totalChunks = (int) Math.ceil((double) fileBytes.length / CHUNK_SIZE);
            if (totalChunks == 0) totalChunks = 1; // empty file

            for (int i = 0; i < totalChunks; i++) {
                int retryCount = 0;
                boolean chunkSent = false;

                while (!chunkSent) {
                    String chunkRequestId = requestId + "_chunk_" + i;
                    CompletableFuture<String> chunkFuture = new CompletableFuture<>();
                    pendingRequests.put(chunkRequestId, chunkFuture);

                    try {
                        int start = i * CHUNK_SIZE;
                        int end = Math.min(start + CHUNK_SIZE, fileBytes.length);
                        byte[] chunk = Arrays.copyOfRange(fileBytes, start, end);
                        String base64Data = Base64.getEncoder().encodeToString(chunk);

                        Map<String, Object> chunkMsg = new HashMap<>();
                        chunkMsg.put("type", "file_upload_chunk");
                        chunkMsg.put("requestId", requestId);
                        chunkMsg.put("index", i);
                        chunkMsg.put("data", base64Data);
                        chunkMsg.put("final", i == totalChunks - 1);
                        sendToAgent(agentId, chunkMsg);

                        // Only wait for the final chunk acknowledgment
                        if (i == totalChunks - 1) {
                            String chunkResponse = chunkFuture.get(UPLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            checkResponseSuccess(chunkResponse);
                        }
                        chunkSent = true;
                    } catch (TimeoutException e) {
                        retryCount++;
                        log.warn("Upload chunk {} timed out (attempt {}/{})", i, retryCount, MAX_CHUNK_RETRIES);
                        if (retryCount >= MAX_CHUNK_RETRIES) {
                            throw e;
                        }
                    } catch (BusinessException e) {
                        retryCount++;
                        log.warn("Upload chunk {} failed: {} (attempt {}/{})", i, e.getMessage(), retryCount, MAX_CHUNK_RETRIES);
                        if (retryCount >= MAX_CHUNK_RETRIES) {
                            throw e;
                        }
                    } finally {
                        pendingRequests.remove(chunkRequestId);
                    }
                }
            }
        } catch (TimeoutException e) {
            throw new BusinessException(504, "Upload timed out");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Upload failed: " + e.getMessage());
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    @Override
    public void mkdir(String agentId, String path) {
        simpleFileOperation(agentId, "file_mkdir", path, OPERATION_TIMEOUT_SECONDS);
    }

    @Override
    public void delete(String agentId, String path) {
        simpleFileOperation(agentId, "file_delete", path, OPERATION_TIMEOUT_SECONDS);
    }

    @Override
    public void rename(String agentId, String oldPath, String newPath) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "file_rename");
            msg.put("requestId", requestId);
            msg.put("oldPath", oldPath);
            msg.put("newPath", newPath);
            sendToAgent(agentId, msg);

            String response = future.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            checkResponseSuccess(response);
        } catch (TimeoutException e) {
            throw new BusinessException(504, "File operation timed out");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("File operation failed: " + e.getMessage());
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    @Override
    public void handleFileResponse(String requestId, String responseJson) {
        // First check download queues (for streaming chunks)
        BlockingQueue<String> downloadQueue = downloadQueues.get(requestId);
        if (downloadQueue != null) {
            downloadQueue.offer(responseJson);
            return;
        }

        // Then check pending futures
        CompletableFuture<String> future = pendingRequests.get(requestId);
        if (future != null) {
            future.complete(responseJson);
            return;
        }

        // Check chunk-specific futures for uploads
        // Upload chunks use requestId + "_chunk_" + index
        // The agent sends back the original requestId, so we need to find matching chunk futures
        for (Map.Entry<String, CompletableFuture<String>> entry : pendingRequests.entrySet()) {
            if (entry.getKey().startsWith(requestId + "_chunk_")) {
                entry.getValue().complete(responseJson);
                return;
            }
        }

        log.warn("No pending request found for requestId: {}", requestId);
    }

    // --- Private helpers ---

    private void simpleFileOperation(String agentId, String type, String path, long timeoutSeconds) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            Map<String, Object> msg = Map.of(
                "type", type,
                "requestId", requestId,
                "path", path
            );
            sendToAgent(agentId, msg);

            String response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            checkResponseSuccess(response);
        } catch (TimeoutException e) {
            throw new BusinessException(504, "File operation timed out");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("File operation failed: " + e.getMessage());
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    private void sendToAgent(String agentId, Map<String, Object> message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            boolean sent = agentHandler.sendToAgent(agentId, json);
            if (!sent) {
                throw new BusinessException(400, "Agent not connected");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to serialize message: " + e.getMessage());
        }
    }

    private void checkResponseSuccess(String responseJson) {
        try {
            JsonNode node = objectMapper.readTree(responseJson);
            if (node.has("success") && !node.get("success").asBoolean()) {
                String error = node.has("error") ? node.get("error").asText() : "Operation failed";
                if (error.contains("access denied") || error.contains("path traversal") ||
                    error.contains("symlink") || error.contains("read-only")) {
                    throw new BusinessException(403, "Access denied");
                }
                throw new BusinessException("File operation failed: " + error);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse agent response: " + e.getMessage());
        }
    }

    private List<FileInfoVO> parseFileListResponse(String responseJson) {
        try {
            JsonNode node = objectMapper.readTree(responseJson);
            if (node.has("success") && !node.get("success").asBoolean()) {
                String error = node.has("error") ? node.get("error").asText() : "List failed";
                if (error.contains("access denied") || error.contains("path traversal") ||
                    error.contains("symlink")) {
                    throw new BusinessException(403, "Access denied");
                }
                throw new BusinessException("File operation failed: " + error);
            }

            List<FileInfoVO> files = new ArrayList<>();
            if (node.has("entries") && node.get("entries").isArray()) {
                for (JsonNode entry : node.get("entries")) {
                    files.add(FileInfoVO.builder()
                        .name(entry.get("name").asText())
                        .dir(entry.has("isDir") && entry.get("isDir").asBoolean())
                        .size(entry.has("size") ? entry.get("size").asLong() : 0)
                        .mode(entry.has("mode") ? entry.get("mode").asText() : "")
                        .modTime(entry.has("modTime") ? entry.get("modTime").asLong() : 0)
                        .build());
                }
            }
            return files;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse file list response: " + e.getMessage());
        }
    }
}
