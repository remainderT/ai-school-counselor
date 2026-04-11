export interface ApiResult<T> {
  code: string;
  message?: string;
  data: T;
}

export interface RetrievalMatch {
  fileMd5?: string;
  chunkId?: number;
  textContent?: string;
  relevanceScore?: number;
  sourceFileName?: string;
}

export interface ChatResponsePayload {
  response: string;
  sources: RetrievalMatch[];
}

export interface FeedbackPayload {
  messageId: number;
  score: number;
  comment?: string;
  userId?: string;
}

export interface KnowledgeItem {
  id: number;
  userId?: string;
  name: string;
  description?: string;
  documentCount?: number;
}

/** 文档处理状态码常量 */
export const DocStatus = {
  PENDING: 0,
  PROCESSING: 1,
  DONE: 2,
  FAILED: -1,
} as const;

export interface DocumentItem {
  id: number;
  knowledgeId?: number;
  originalFileName?: string;
  fileName?: string;
  fileSizeBytes?: number;
  /** 后端 processingStatus: 0=待处理, 1=处理中, 2=已完成, -1=失败 */
  processingStatus?: number;
  processingStatusDesc?: string;
  createTime?: string;
  chunkCount?: number;
}

export interface ChunkItem {
  id: number;
  documentId: number;
  fragmentIndex: number;
  textData?: string;
  encodingModel?: string;
  md5Hash?: string;
  tokenEstimate?: number;
}

export interface StreamEvent {
  event: string;
  data: unknown;
}

export interface ConversationSessionItem {
  sessionId: string;
  userId: string;
  title: string;
  messageCount: number;
  updatedAt: string;
}

export interface ConversationMessageItem {
  id: number;
  role: "user" | "assistant";
  content: string;
  createdAt: string;
  sources?: RetrievalMatch[];
}

export interface IntentNodeItem {
  id: number;
  nodeId: string;
  nodeName: string;
  parentId?: string;
  nodeType?: string;
  description?: string;
  promptTemplate?: string;
  promptSnippet?: string;
  paramPromptTemplate?: string;
  keywords?: string[];
  knowledgeBaseId?: number;  // 后端返回 Long 类型，前端使用 number
  actionService?: string;
  mcpToolId?: string;
  topK?: number;
  enabled?: number;
  children?: IntentNodeItem[];
}
