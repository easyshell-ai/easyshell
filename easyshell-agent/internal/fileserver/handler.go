package fileserver

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

// protectedPaths are top level directories that must never be deleted or renamed.
// The check is prefix based: "/etc/nginx" is protected just like "/etc" itself, which the
// previous exact-match map allowed through.
var protectedPaths = []string{
	"/", "/bin", "/sbin", "/etc",
	"/usr", "/var", "/lib", "/lib64",
	"/boot", "/proc", "/sys", "/dev",
	"/home", "/root", "/tmp", "/run",
	"/opt", "/srv", "/mnt", "/media",
}

// isProtectedPath reports whether a path may not be deleted or renamed.
//
// Two rules keep the file manager fully usable while making it impossible to destroy the
// host by accident:
//   - inside a dedicated file root (the default) nothing is protected, so the operator can
//     manage their own files freely;
//   - when the root is a system directory (explicit allow-system-root opt-in) only the
//     protected directories themselves are off limits ("/etc" cannot be removed), while
//     their contents stay manageable as before.
var protectedPathsSet = func() map[string]bool {
	set := make(map[string]bool, len(protectedPaths))
	for _, p := range protectedPaths {
		set[p] = true
	}
	return set
}()

func isProtectedPath(path string, root string) bool {
	clean := filepath.Clean(path)
	rootClean := filepath.Clean(root)

	if rootClean != "/" && (clean == rootClean || strings.HasPrefix(clean, rootClean+"/")) {
		return false
	}

	if systemRoots[rootClean] {
		return protectedPathsSet[clean]
	}

	for _, protected := range protectedPaths {
		if clean == protected || strings.HasPrefix(clean, protected+"/") {
			return true
		}
	}
	return false
}

type uploadState struct {
	file      *os.File
	tempPath  string
	destPath  string
	received  int64
	expected  int64
}

type Handler struct {
	config    *SecurityConfig
	uploads   map[string]*uploadState
	uploadsMu sync.Mutex
}

const ChunkSize = 256 * 1024

func NewHandler(config *SecurityConfig) *Handler {
	return &Handler{
		config:  config,
		uploads: make(map[string]*uploadState),
	}
}

func (h *Handler) HandleMessage(data []byte, send func(interface{})) {
	var raw map[string]interface{}
	if err := json.Unmarshal(data, &raw); err != nil {
		slog.Error("failed to decode file message", "error", err)
		return
	}

	msgType, _ := raw["type"].(string)
	reqID, _ := raw["requestId"].(string)

	if !h.config.Enabled {
		send(FileOperationResult{
			Type:      "file_result",
			RequestID: reqID,
			Success:   false,
			Error:     "file server is disabled",
		})
		return
	}

	switch msgType {
	case "file_list":
		h.handleList(data, reqID, send)
	case "file_download":
		h.handleDownload(data, reqID, send)
	case "file_upload_start":
		h.handleUploadStart(data, reqID, send)
	case "file_upload_chunk":
		h.handleUploadChunk(data, reqID, send)
	case "file_mkdir":
		h.handleMkdir(data, reqID, send)
	case "file_delete":
		h.handleDelete(data, reqID, send)
	case "file_rename":
		h.handleRename(data, reqID, send)
	default:
		send(FileOperationResult{
			Type:      "file_result",
			RequestID: reqID,
			Success:   false,
			Error:     "unknown file operation type",
		})
	}
}

func (h *Handler) handleList(data []byte, reqID string, send func(interface{})) {
	var req FileListRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid list request", send)
		return
	}

	absPath, err := h.config.ValidatePath(req.Path)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	entries, err := os.ReadDir(absPath)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	var fileInfos []FileInfo
	for _, entry := range entries {
		if len(fileInfos) >= h.config.MaxListEntries {
			break
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		fileInfos = append(fileInfos, FileInfo{
			Name:    info.Name(),
			IsDir:   info.IsDir(),
			Size:    info.Size(),
			Mode:    info.Mode().String(),
			ModTime: info.ModTime().UnixMilli(),
		})
	}

	sort.Slice(fileInfos, func(i, j int) bool {
		if fileInfos[i].IsDir != fileInfos[j].IsDir {
			return fileInfos[i].IsDir
		}
		return strings.ToLower(fileInfos[i].Name) < strings.ToLower(fileInfos[j].Name)
	})

	send(FileListResponse{
		Type:      "file_list_result",
		RequestID: reqID,
		Success:   true,
		Path:      req.Path,
		Entries:   fileInfos,
	})
}

func (h *Handler) handleDownload(data []byte, reqID string, send func(interface{})) {
	var req FileDownloadRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid download request", send)
		return
	}

	absPath, err := h.config.ValidatePath(req.Path)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	info, err := os.Stat(absPath)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}
	if info.IsDir() {
		h.sendError(reqID, "cannot download directory", send)
		return
	}
	if err := h.config.ValidateFileSize(info.Size()); err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	file, err := os.Open(absPath)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}
	defer file.Close()

	buf := make([]byte, ChunkSize)
	index := 0
	totalSize := info.Size()

	for {
		n, err := file.Read(buf)
		if n > 0 {
			// Check if this is the last chunk by seeing if we've read to EOF
			// or if the next read would be EOF
			isFinal := err == io.EOF
			if !isFinal && n < ChunkSize {
				// If we read less than a full chunk and no error, peek ahead
				// This handles cases where Read returns partial data before EOF
				isFinal = true
			}
			chunk := FileDownloadChunk{
				Type:      "file_download_chunk",
				RequestID: reqID,
				Index:     index,
				Data:      base64.StdEncoding.EncodeToString(buf[:n]),
				Final:     isFinal,
			}
			if index == 0 {
				chunk.TotalSize = totalSize
			}
			send(chunk)
			index++
			
			if isFinal {
				break
			}
		}
		if err != nil {
			if err != io.EOF {
				h.sendError(reqID, "read error: "+err.Error(), send)
			} else if index == 0 {
				// Empty file case
				send(FileDownloadChunk{
					Type:      "file_download_chunk",
					RequestID: reqID,
					Index:     0,
					Data:      "",
					Final:     true,
					TotalSize: 0,
				})
			}
			break
		}
	}
}

func (h *Handler) handleUploadStart(data []byte, reqID string, send func(interface{})) {
	var req FileUploadStartRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid upload start request", send)
		return
	}

	absPath, err := h.config.ValidateWriteTarget(req.Path)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}
	if isProtectedPath(absPath, h.config.RootPath) {
		h.sendError(reqID, "cannot write to system protected path", send)
		return
	}
	if req.Size < 0 {
		h.sendError(reqID, "invalid upload size", send)
		return
	}
	if err := h.config.ValidateFileSize(req.Size); err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	tempFile, err := os.CreateTemp(filepath.Dir(absPath), ".easyshell_upload_"+reqID+"_*")
	if err != nil {
		h.sendError(reqID, "failed to create temp file: "+err.Error(), send)
		return
	}

	h.uploadsMu.Lock()
	h.uploads[reqID] = &uploadState{
		file:     tempFile,
		tempPath: tempFile.Name(),
		destPath: absPath,
		expected: req.Size,
	}
	h.uploadsMu.Unlock()

	send(FileOperationResult{Type: "file_result", RequestID: reqID, Success: true})
}

func (h *Handler) handleUploadChunk(data []byte, reqID string, send func(interface{})) {
	var req FileUploadChunkRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid upload chunk request", send)
		return
	}

	h.uploadsMu.Lock()
	state, ok := h.uploads[reqID]
	h.uploadsMu.Unlock()

	if !ok {
		h.sendError(reqID, "upload session not found", send)
		return
	}

	decoded, err := base64.StdEncoding.DecodeString(req.Data)
	if err != nil {
		h.sendError(reqID, "invalid base64 chunk", send)
		return
	}

	n, err := state.file.Write(decoded)
	if err != nil {
		h.sendError(reqID, "write error: "+err.Error(), send)
		return
	}
	state.received += int64(n)

	// The declared size is only a hint: enforce both it and the configured maximum on the
	// bytes actually received, otherwise a client can write unbounded data to disk.
	if state.expected > 0 && state.received > state.expected {
		state.file.Close()
		h.uploadsMu.Lock()
		delete(h.uploads, reqID)
		h.uploadsMu.Unlock()
		_ = os.Remove(state.tempPath)
		h.sendError(reqID, fmt.Sprintf("upload exceeds declared size of %d bytes", state.expected), send)
		return
	}
	if err := h.config.ValidateFileSize(state.received); err != nil {
		state.file.Close()
		h.uploadsMu.Lock()
		delete(h.uploads, reqID)
		h.uploadsMu.Unlock()
		_ = os.Remove(state.tempPath)
		h.sendError(reqID, err.Error(), send)
		return
	}

	if req.Final {
		state.file.Close()
		h.uploadsMu.Lock()
		delete(h.uploads, reqID)
		h.uploadsMu.Unlock()

		if err := os.Rename(state.tempPath, state.destPath); err != nil {
			h.sendError(reqID, "failed to commit file: "+err.Error(), send)
			return
		}
	}

	send(FileOperationResult{Type: "file_result", RequestID: reqID, Success: true})
}

func (h *Handler) handleMkdir(data []byte, reqID string, send func(interface{})) {
	var req FileMkdirRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid mkdir request", send)
		return
	}

	absPath, err := h.config.ValidateWriteTarget(req.Path)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	if err := os.MkdirAll(absPath, 0755); err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	send(FileOperationResult{Type: "file_result", RequestID: reqID, Success: true})
}

func (h *Handler) handleDelete(data []byte, reqID string, send func(interface{})) {
	var req FileDeleteRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid delete request", send)
		return
	}

	absPath, err := h.config.ValidateWrite(req.Path)
	if err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	if isProtectedPath(absPath, h.config.RootPath) {
		h.sendError(reqID, "cannot delete system protected path", send)
		return
	}

	if err := os.RemoveAll(absPath); err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	send(FileOperationResult{Type: "file_result", RequestID: reqID, Success: true})
}

func (h *Handler) handleRename(data []byte, reqID string, send func(interface{})) {
	var req FileRenameRequest
	if err := json.Unmarshal(data, &req); err != nil {
		h.sendError(reqID, "invalid rename request", send)
		return
	}

	oldAbs, err := h.config.ValidateWrite(req.OldPath)
	if err != nil {
		h.sendError(reqID, "old path: "+err.Error(), send)
		return
	}
	newAbs, err := h.config.ValidateWriteTarget(req.NewPath)
	if err != nil {
		h.sendError(reqID, "new path: "+err.Error(), send)
		return
	}

	if isProtectedPath(oldAbs, h.config.RootPath) {
		h.sendError(reqID, "cannot rename system protected path", send)
		return
	}
	if isProtectedPath(newAbs, h.config.RootPath) {
		h.sendError(reqID, "cannot rename onto a system protected path", send)
		return
	}

	if err := os.Rename(oldAbs, newAbs); err != nil {
		h.sendError(reqID, err.Error(), send)
		return
	}

	send(FileOperationResult{Type: "file_result", RequestID: reqID, Success: true})
}

func (h *Handler) sendError(reqID string, errStr string, send func(interface{})) {
	send(FileOperationResult{
		Type:      "file_result",
		RequestID: reqID,
		Success:   false,
		Error:     errStr,
	})
}

func (h *Handler) CleanupStaleTempFiles() {
	if !h.config.Enabled {
		return
	}
	root := h.config.RootPath
	_ = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return filepath.SkipDir
		}
		if info.IsDir() {
			if path != root {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(filepath.Base(path), ".easyshell_upload_") {
			slog.Info("cleaning up stale temp file", "path", path)
			_ = os.Remove(path)
		}
		return nil
	})
}
