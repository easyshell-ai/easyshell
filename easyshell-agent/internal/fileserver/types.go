package fileserver

// === Request messages (Server → Agent via WebSocket) ===

type FileListRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Path      string `json:"path"`
}

type FileDownloadRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Path      string `json:"path"`
}

type FileUploadStartRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Path      string `json:"path"`
	Size      int64  `json:"size"`
}

type FileUploadChunkRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Index     int    `json:"index"`
	Data      string `json:"data"`
	Final     bool   `json:"final"`
}

type FileMkdirRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Path      string `json:"path"`
}

type FileDeleteRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Path      string `json:"path"`
}

type FileRenameRequest struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	OldPath   string `json:"oldPath"`
	NewPath   string `json:"newPath"`
}

// === Response messages (Agent → Server via WebSocket) ===

type FileListResponse struct {
	Type      string     `json:"type"`
	RequestID string     `json:"requestId"`
	Success   bool       `json:"success"`
	Error     string     `json:"error,omitempty"`
	Path      string     `json:"path"`
	Entries   []FileInfo `json:"entries,omitempty"`
}

type FileInfo struct {
	Name    string `json:"name"`
	IsDir   bool   `json:"isDir"`
	Size    int64  `json:"size"`
	Mode    string `json:"mode"`
	ModTime int64  `json:"modTime"`
}

type FileDownloadChunk struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Index     int    `json:"index"`
	Data      string `json:"data"`
	Final     bool   `json:"final"`
	TotalSize int64  `json:"totalSize"`
}

type FileOperationResult struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	Success   bool   `json:"success"`
	Error     string `json:"error,omitempty"`
}
