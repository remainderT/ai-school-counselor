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

export interface DocumentItem {
  id: number;
  knowledgeId?: number;
  originalFileName?: string;
  fileName?: string;
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
  knowledgeBaseId?: string;
  actionService?: string;
  mcpToolId?: string;
  topK?: number;
  sortOrder?: number;
  enabled?: number;
  children?: IntentNodeItem[];
}
