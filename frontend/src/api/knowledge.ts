import { apiUrl, authFetch } from './http'

export interface KnowledgeUploadResult {
  taskId: string
}

export interface DocumentTaskStatus {
  status: string
  errorMsg?: string | null
}

/** 与后端 DocumentMetadataResponse 对齐 */
export interface DocumentMetadataItem {
  taskId: string
  fileName: string
  fileSize: number
  scopeType: string
  status: string
  chunkCount?: number | null
  errorMsg?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface DocumentMetadataPage {
  records: DocumentMetadataItem[]
  total: number
  current: number
  size: number
}

export interface MultipartInitResponse {
  taskId: string
  uploadId: string
  minioPath: string
}

export interface MultipartPartInfo {
  partNumber: number
  etag: string
}

async function parseError(r: Response): Promise<string> {
  const data = await r.json().catch(() => ({}))
  return (data as { message?: string })?.message ?? `HTTP ${r.status}`
}

/**
 * 用户上传私有知识库（multipart，字段名 file）。小文件直传，服务端流式写入 RustFS。
 */
export async function uploadUserKnowledge(file: File): Promise<KnowledgeUploadResult> {
  const fd = new FormData()
  fd.append('file', file)
  const r = await authFetch('/api/knowledge/upload', {
    method: 'POST',
    body: fd
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as KnowledgeUploadResult
}

/**
 * 管理员上传公共知识库。
 */
export async function uploadAdminKnowledge(file: File): Promise<KnowledgeUploadResult> {
  const fd = new FormData()
  fd.append('file', file)
  const r = await authFetch('/api/admin/knowledge/upload', {
    method: 'POST',
    body: fd
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as KnowledgeUploadResult
}

/**
 * 分片上传初始化（uploadId 与 taskId 相同，与后端约定一致）。
 */
export async function initMultipartUpload(
  fileName: string,
  fileSize: number,
  contentType: string,
  scope: 'private' | 'public'
): Promise<MultipartInitResponse> {
  const path = scope === 'public' ? '/api/admin/knowledge/upload/init' : '/api/knowledge/upload/init'
  const r = await authFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      fileName,
      fileSize,
      contentType: contentType || 'application/octet-stream'
    })
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as MultipartInitResponse
}

/**
 * 上传单个分片。
 */
export async function uploadChunk(
  taskId: string,
  uploadId: string,
  minioPath: string,
  partNumber: number,
  chunk: Blob
): Promise<{ etag: string }> {
  const fd = new FormData()
  fd.append('taskId', taskId)
  fd.append('uploadId', uploadId)
  fd.append('minioPath', minioPath)
  fd.append('partNumber', String(partNumber))
  fd.append('chunk', chunk, `part-${partNumber}`)
  const r = await authFetch('/api/knowledge/upload/chunk', {
    method: 'POST',
    body: fd
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as { etag: string }
}

/**
 * 完成分片合并并入队解析。
 */
export async function completeMultipartUpload(
  taskId: string,
  uploadId: string,
  minioPath: string,
  parts: MultipartPartInfo[]
): Promise<KnowledgeUploadResult> {
  const r = await authFetch('/api/knowledge/upload/complete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskId, uploadId, minioPath, parts })
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as KnowledgeUploadResult
}

/**
 * 取消分片上传。
 */
export async function abortMultipartUpload(taskId: string, uploadId: string, minioPath: string): Promise<void> {
  const r = await authFetch('/api/knowledge/upload/abort', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskId, uploadId, minioPath })
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
}

/**
 * 轮询文档解析任务状态。
 */
export async function pollTaskStatus(taskId: string): Promise<DocumentTaskStatus> {
  const r = await authFetch(`/api/knowledge/tasks/${encodeURIComponent(taskId)}`)
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as DocumentTaskStatus
}

/**
 * 当前用户上传任务分页。
 */
export async function listMyDocuments(page = 1, size = 20): Promise<DocumentMetadataPage> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  const r = await authFetch(`/api/knowledge/documents?${q}`)
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as DocumentMetadataPage
}

/**
 * 管理员：全部上传任务。
 */
export async function listAllDocuments(page = 1, size = 20): Promise<DocumentMetadataPage> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  const r = await authFetch(`/api/admin/knowledge/documents?${q}`)
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
  return (await r.json()) as DocumentMetadataPage
}

/**
 * 删除文档任务（本人或管理员）。
 */
export async function deleteDocument(taskId: string): Promise<void> {
  const r = await authFetch(`/api/knowledge/documents/${encodeURIComponent(taskId)}`, {
    method: 'DELETE'
  })
  if (!r.ok) {
    throw new Error(await parseError(r))
  }
}
