<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { 
  MessageSquare, Files, Sparkles, Send, Paperclip, 
  Settings, Menu, Bot, User, History, CloudUpload, Search, Trash2, LogIn, Zap, Database, Moon, Sun
} from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import MarkdownIt from 'markdown-it'

// --- Types ---
interface Source {
  filename: string
  content: string
  score: number
}

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
  timestamp: number
}

interface Document {
  id?: number
  md5Hash: string
  originalFileName: string
  fileSizeBytes: number
  department: string
  policyYear: string
  uploadedAt: number
  processingStatus?: number
}

// --- State ---
const currentView = ref('chat')
const userInput = ref('')
const isTyping = ref(false)
const isSearching = ref(false)
const searchSteps = ref<string[]>([])
const messages = ref<Message[]>([])
const history = ref([{ id: 1, title: '奖学金政策咨询' }, { id: 2, title: '选课系统指南' }])
const user = ref(null)
try {
  const savedUser = localStorage.getItem('user')
  if (savedUser && savedUser !== 'undefined') {
    user.value = JSON.parse(savedUser)
  }
} catch (e) {
  console.error('Failed to parse user from localStorage', e)
  localStorage.removeItem('user')
}
const showLogin = ref(false)
const showUpload = ref(false)
const loadingDocs = ref(false)
const documents = ref<Document[]>([])
const showSourceDrawer = ref(false)
const selectedSource = ref<Source | null>(null)

// 主题管理
const isDarkTheme = ref(true)
try {
  const savedTheme = localStorage.getItem('theme')
  if (savedTheme) {
    isDarkTheme.value = savedTheme === 'dark'
  }
} catch (e) {
  console.error('Failed to load theme from localStorage', e)
}

const loginForm = reactive({ email: '', code: '' })
const uploadMeta = reactive({ 
  userId: 'anonymous',
  department: '', 
  policyYear: '2025' 
})
const docFilter = reactive({ search: '', department: '', year: '' })
const md = new MarkdownIt()

const suggestedQuestions = [
  '🎓 2024年国家奖学金申请条件是什么？',
  '🌐 校园网忘记密码如何重置？',
  '📚 图书馆借书上限是多少本？',
  '🏥 校内医保报销流程是怎么样的？'
]

// --- Computed ---
const filteredDocuments = computed(() => {
  return documents.value.filter(doc => {
    const matchSearch = !docFilter.search || doc.originalFileName.toLowerCase().includes(docFilter.search.toLowerCase())
    const matchDept = !docFilter.department || doc.department === docFilter.department
    const matchYear = !docFilter.year || doc.policyYear === docFilter.year
    return matchSearch && matchDept && matchYear
  })
})

// --- Methods ---
const renderMarkdown = (text: string) => md.render(text)
const formatTime = (ts: number) => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
const formatDate = (ts: number) => new Date(ts).toLocaleDateString()
const formatFileSize = (bytes: number) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const scrollToBottom = async () => {
  await nextTick()
  const container = document.getElementById('message-container')
  if (container) container.scrollTop = container.scrollHeight
}

const sendMessage = async () => {
  if (!userInput.value.trim() || isTyping.value) return

  const query = userInput.value
  messages.value.push({ role: 'user', content: query, timestamp: Date.now() })
  userInput.value = ''
  isTyping.value = true
  isSearching.value = true
  searchSteps.value = ['正在理解问题语义...', '正在检索校园知识库...', '正在重排序相关片段...']
  scrollToBottom()

  const assistantMsg = reactive<Message>({ role: 'assistant', content: '', sources: [], timestamp: Date.now() })
  messages.value.push(assistantMsg)

  try {
    const url = `/api/rag/chat/stream?message=${encodeURIComponent(query)}&userId=${user.value?.id || 'anonymous'}`
    console.log('发送请求到:', url)
    
    const response = await fetch(url)
    console.log('响应状态:', response.status, response.statusText)
    console.log('响应头:', response.headers.get('content-type'))
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }
    
    if (!response.body) throw new Error('ReadableStream not supported')
    
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let chunkCount = 0
    
    isSearching.value = false

    while (true) {
      const { value, done } = await reader.read()
      if (done) {
        console.log('流结束，共接收', chunkCount, '个chunk')
        break
      }
      
      chunkCount++
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      
      console.log('收到chunk', chunkCount, ':', lines.length, '行')
      
      for (const line of lines) {
        if (!line.trim()) continue
        
        console.log('处理行:', line.substring(0, 100))
        
        if (line.startsWith('event:')) {
          continue
        } else if (line.startsWith('data:')) {
          const data = line.substring(5).trim()
          
          try {
            const jsonData = JSON.parse(data)
            console.log('收到JSON数据:', jsonData)
            if (Array.isArray(jsonData)) {
              assistantMsg.sources = jsonData
            }
          } catch (e) {
            if (data && data !== '[DONE]') {
              console.log('添加文本内容:', data.substring(0, 50))
              assistantMsg.content += data
              scrollToBottom()
            }
          }
        }
      }
    }
  } catch (error) {
    console.error('Chat error:', error)
    assistantMsg.content = "抱歉，服务器暂时无法连接。错误：" + error.message
  } finally {
    isTyping.value = false
    isSearching.value = false
  }
}

const fetchDocs = async () => {
  loadingDocs.value = true
  try {
    const res = await axios.get('/api/rag/document/list', {
      params: { userId: user.value?.id || 'anonymous' }
    })
    documents.value = res.data.data || []
  } catch (err) {
    ElMessage.error('获取文档失败')
    documents.value = []
  } finally {
    loadingDocs.value = false
  }
}

const deleteDoc = async (md5Hash: string) => {
  try {
    await axios.delete(`/api/rag/document/${md5Hash}`, {
      params: { userId: user.value?.id || 'anonymous' }
    })
    ElMessage.success('文档已删除')
    fetchDocs()
  } catch (err) {
    ElMessage.error('删除失败')
  }
}

const handleLogin = async () => {
  try {
    const res = await axios.post('/api/rag/user/login', loginForm)
    user.value = res.data.data || res.data
    localStorage.setItem('user', JSON.stringify(res.data.data || res.data))
    showLogin.value = false
    ElMessage.success('欢迎回来，' + (res.data.data?.username || res.data.username))
  } catch (err) {
    ElMessage.error('验证码错误或登录失败')
  }
}

const sendCode = async () => {
  if (!loginForm.email) return ElMessage.warning('请输入邮箱')
  try {
    await axios.get(`/api/rag/user/send-code?mail=${loginForm.email}`)
    ElMessage.success('验证码已发送')
  } catch (err) {
    ElMessage.error('发送失败')
  }
}

const onUploadSuccess = () => {
  ElMessage.success('文件上传并解析成功')
  showUpload.value = false
  uploadMeta.userId = user.value?.id || 'anonymous'
  fetchDocs()
}

const openSourceDetail = (source: Source) => {
  selectedSource.value = source
  showSourceDrawer.value = true
}

const toggleTheme = () => {
  console.log('切换主题，当前状态:', isDarkTheme.value)
  isDarkTheme.value = !isDarkTheme.value
  console.log('切换后状态:', isDarkTheme.value)
  localStorage.setItem('theme', isDarkTheme.value ? 'dark' : 'light')
}

onMounted(() => {
  fetchDocs()
})
</script>

<template>
  <div class="app-container" :class="{ 'light-theme': !isDarkTheme }">
    <!-- 背景动画层 -->
    <div class="background-layer">
      <div class="gradient-orb orb-1"></div>
      <div class="gradient-orb orb-2"></div>
      <div class="gradient-orb orb-3"></div>
      <div class="grid-overlay"></div>
    </div>

    <!-- 主要内容区 -->
    <div class="main-wrapper">
      <!-- 侧边栏 -->
      <aside class="sidebar" :class="{ hidden: false }">
        <div class="sidebar-header">
          <div class="logo-section">
            <div class="logo-icon">
              <Zap class="logo-svg" />
            </div>
            <div class="logo-text">
              <h1 class="logo-title">智慧校园</h1>
              <p class="logo-subtitle">AI ASSISTANT</p>
            </div>
          </div>
        </div>
        
        <nav class="sidebar-nav">
          <button 
            @click="currentView = 'chat'" 
            :class="['nav-item', { active: currentView === 'chat' }]"
          >
            <MessageSquare class="nav-icon" />
            <span class="nav-label">智能对话</span>
            <div class="nav-indicator"></div>
          </button>
          
          <button 
            @click="currentView = 'docs'" 
            :class="['nav-item', { active: currentView === 'docs' }]"
          >
            <Database class="nav-icon" />
            <span class="nav-label">知识库</span>
            <div class="nav-indicator"></div>
          </button>
        </nav>

        <div class="sidebar-footer">
          <div v-if="user" class="user-profile">
            <el-avatar :size="40" :src="user.avatar">{{ user.username[0] }}</el-avatar>
            <div class="user-info">
              <p class="user-name">{{ user.username }}</p>
              <p class="user-email">{{ user.email }}</p>
            </div>
          </div>
          <button v-else @click="showLogin = true" class="login-btn">
            <LogIn class="btn-icon" />
            <span>登录账号</span>
          </button>
        </div>
      </aside>

      <!-- 主内容区 -->
      <main class="main-content">
        <!-- 主题切换按钮 - 右上角 -->
        <button @click="toggleTheme" class="theme-toggle-floating">
          <Moon v-if="isDarkTheme" class="theme-icon" />
          <Sun v-else class="theme-icon" />
        </button>

        <!-- 聊天视图 -->
        <div v-if="currentView === 'chat'" class="chat-view">
          <div id="message-container" class="messages-container">
            <!-- 欢迎界面 -->
            <div v-if="messages.length === 0" class="welcome-screen">
              <div class="welcome-icon">
                <Sparkles class="sparkle-icon" />
              </div>
              <h2 class="welcome-title">你好！我是智慧校园助手</h2>
              <p class="welcome-desc">基于先进的 RAG 检索增强生成技术，为您提供精准的校园服务</p>
              
              <div class="suggested-questions">
                <button 
                  v-for="q in suggestedQuestions" 
                  :key="q" 
                  @click="userInput = q.substring(3); sendMessage()" 
                  class="suggestion-card"
                >
                  <span class="suggestion-text">{{ q }}</span>
                </button>
              </div>
            </div>

            <!-- 搜索动画 -->
            <div v-if="isSearching" class="search-animation">
              <div class="search-icon-wrapper">
                <div class="pulse-ring"></div>
                <Bot class="search-bot-icon" />
              </div>
              <div class="search-steps">
                <p class="search-title">AI 正在思考中...</p>
                <div v-for="(step, idx) in searchSteps" :key="idx" class="search-step">
                  <div class="step-dot"></div>
                  <span>{{ step }}</span>
                </div>
              </div>
            </div>

            <!-- 消息列表 -->
            <div v-for="(msg, idx) in messages" :key="idx" class="message-wrapper" :class="msg.role">
              <div class="message-avatar">
                <User v-if="msg.role === 'user'" class="avatar-icon" />
                <Bot v-else class="avatar-icon" />
              </div>
              
              <div class="message-content">
                <div class="message-bubble" :class="msg.role">
                  <div v-html="renderMarkdown(msg.content)" class="markdown-body"></div>
                  
                  <!-- 来源引用 -->
                  <div v-if="msg.sources && msg.sources.length > 0" class="sources-section">
                    <p class="sources-title">参考来源</p>
                    <div class="sources-list">
                      <div 
                        v-for="(s, sIdx) in msg.sources" 
                        :key="sIdx" 
                        @click="openSourceDetail(s)"
                        class="source-tag"
                      >
                        <Files class="source-icon" />
                        <span>[{{ sIdx + 1 }}] {{ s.filename }}</span>
                      </div>
                    </div>
                  </div>
                </div>
                <span class="message-time">{{ formatTime(msg.timestamp) }}</span>
              </div>
            </div>
          </div>

          <!-- 输入区域 -->
          <div class="input-section">
            <div class="input-container">
              <button class="input-action-btn">
                <Paperclip class="action-icon" />
              </button>
              
              <textarea 
                v-model="userInput" 
                @keydown.enter.prevent="sendMessage" 
                placeholder="询问关于校园的任何问题..."
                class="message-input"
                rows="1"
              ></textarea>
              
              <button 
                @click="sendMessage" 
                :disabled="!userInput.trim() || isTyping"
                class="send-btn"
                :class="{ active: userInput.trim() && !isTyping }"
              >
                <Send class="send-icon" />
              </button>
            </div>
          </div>
        </div>

        <!-- 知识库视图 -->
        <div v-else class="docs-view">
          <div class="docs-header">
            <div class="docs-title-section">
              <h2 class="docs-title">知识库管理</h2>
              <p class="docs-subtitle">上传文档以增强 AI 的知识能力</p>
            </div>
            <button @click="showUpload = true" class="upload-btn">
              <CloudUpload class="btn-icon" />
              <span>上传文档</span>
            </button>
          </div>

          <div class="docs-filters">
            <div class="search-box">
              <Search class="search-icon" />
              <input 
                v-model="docFilter.search" 
                placeholder="搜索文件名称..." 
                class="search-input"
              />
            </div>
            <el-select v-model="docFilter.department" placeholder="全部部门" clearable class="filter-select">
              <el-option label="教务处" value="教务处" />
              <el-option label="学生处" value="学生处" />
            </el-select>
          </div>

          <div class="docs-table-container">
            <el-table :data="filteredDocuments" v-loading="loadingDocs" class="docs-table">
              <el-table-column label="文件名" min-width="300">
                <template #default="{row}">
                  <div class="file-cell">
                    <div class="file-icon">{{ row.originalFileName.split('.').pop() }}</div>
                    <span class="file-name">{{ row.originalFileName }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="department" label="发布部门" width="150" />
              <el-table-column label="大小" width="120">
                <template #default="{row}">{{ formatFileSize(row.fileSizeBytes) }}</template>
              </el-table-column>
              <el-table-column label="上传日期" width="180">
                <template #default="{row}">{{ formatDate(row.uploadedAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="{row}">
                  <button @click="deleteDoc(row.md5Hash)" class="delete-btn">
                    <Trash2 class="delete-icon" />
                  </button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </main>
    </div>

    <!-- 登录对话框 -->
    <el-dialog v-model="showLogin" title="欢迎登录" width="400px" class="custom-dialog">
      <div class="dialog-content">
        <el-input v-model="loginForm.email" placeholder="输入邮箱地址" size="large" />
        <div class="code-input-group">
          <el-input v-model="loginForm.code" placeholder="验证码" size="large" />
          <el-button @click="sendCode" size="large">获取验证码</el-button>
        </div>
        <el-button type="primary" class="login-submit-btn" @click="handleLogin">立即登录</el-button>
      </div>
    </el-dialog>

    <!-- 上传对话框 -->
    <el-dialog v-model="showUpload" title="上传校园文档" width="500px" class="custom-dialog">
      <div class="upload-dialog-content">
        <el-upload
          drag
          action="/api/rag/document/upload"
          :data="uploadMeta"
          :on-success="onUploadSuccess"
          :before-upload="() => { uploadMeta.userId = user?.id || 'anonymous' }"
          class="upload-area"
        >
          <div class="upload-hint">
            <CloudUpload class="upload-icon" />
            <div class="upload-text">拖拽文件到此处 或 <span class="upload-link">点击上传</span></div>
            <p class="upload-format">支持 PDF, DOCX, XLSX (最大 50MB)</p>
          </div>
        </el-upload>
        <div class="upload-meta">
          <el-select v-model="uploadMeta.department" placeholder="所属部门" size="large">
            <el-option label="教务处" value="教务处" />
            <el-option label="学生处" value="学生处" />
            <el-option label="网信中心" value="网信中心" />
          </el-select>
          <el-select v-model="uploadMeta.policyYear" placeholder="政策年份" size="large">
            <el-option label="2025" value="2025" />
            <el-option label="2024" value="2024" />
          </el-select>
        </div>
      </div>
    </el-dialog>

    <!-- 来源详情抽屉 -->
    <el-drawer
      v-model="showSourceDrawer"
      title="引用来源详情"
      direction="rtl"
      size="400px"
      class="source-drawer"
    >
      <div v-if="selectedSource" class="source-detail">
        <div class="source-header">
          <div class="source-icon-wrapper">
            <Files class="source-file-icon" />
          </div>
          <div class="source-info">
            <h4 class="source-filename">{{ selectedSource.filename }}</h4>
            <p class="source-score">匹配得分: {{ (selectedSource.score * 100).toFixed(1) }}%</p>
          </div>
        </div>

        <div class="source-content">
          <p class="source-label">检索片段预览</p>
          <div class="source-text">
            "{{ selectedSource.content }}"
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* ===== 全局样式与动画 ===== */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

.app-container {
  position: relative;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  background: #0a0e27;
  color: #e5e7eb;
}

/* 背景动画层 */
.background-layer {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
  overflow: hidden;
}

.gradient-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.6;
  animation: float 20s ease-in-out infinite;
}

.orb-1 {
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, #667eea 0%, rgba(102, 126, 234, 0) 70%);
  top: -100px;
  left: -100px;
  animation-delay: 0s;
}

.orb-2 {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, #f093fb 0%, rgba(240, 147, 251, 0) 70%);
  bottom: -50px;
  right: -50px;
  animation-delay: 7s;
}

.orb-3 {
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, #4facfe 0%, rgba(79, 172, 254, 0) 70%);
  top: 50%;
  left: 50%;
  animation-delay: 14s;
}

@keyframes float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  25% { transform: translate(50px, -50px) scale(1.1); }
  50% { transform: translate(-30px, 50px) scale(0.9); }
  75% { transform: translate(30px, 30px) scale(1.05); }
}

.grid-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image: 
    linear-gradient(rgba(102, 126, 234, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(102, 126, 234, 0.03) 1px, transparent 1px);
  background-size: 50px 50px;
  opacity: 0.5;
}

/* 主容器 */
.main-wrapper {
  position: relative;
  z-index: 1;
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden; /* 防止内容溢出 */
}

/* ===== 侧边栏样式 ===== */
.sidebar {
  width: 280px;
  background: rgba(15, 23, 42, 0.7);
  backdrop-filter: blur(20px);
  border-right: 1px solid rgba(102, 126, 234, 0.1);
  display: flex;
  flex-direction: column;
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.sidebar-header {
  padding: 2rem 1.5rem;
  border-bottom: 1px solid rgba(102, 126, 234, 0.1);
}

.logo-section {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.logo-icon {
  width: 48px;
  height: 48px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 16px rgba(102, 126, 234, 0.3);
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.05); }
}

.logo-svg {
  width: 24px;
  height: 24px;
  color: white;
}

.logo-text {
  flex: 1;
}

.logo-title {
  font-size: 1.25rem;
  font-weight: 700;
  background: linear-gradient(135deg, #667eea 0%, #f093fb 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.logo-subtitle {
  font-size: 0.75rem;
  color: #94a3b8;
  font-weight: 600;
  letter-spacing: 2px;
  margin-top: 2px;
}

.sidebar-nav {
  flex: 1;
  padding: 1.5rem 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.875rem 1rem;
  background: transparent;
  border: none;
  border-radius: 12px;
  color: #94a3b8;
  cursor: pointer;
  transition: all 0.3s ease;
  overflow: hidden;
}

.nav-item:hover {
  background: rgba(102, 126, 234, 0.1);
  color: #e5e7eb;
  transform: translateX(4px);
}

.nav-item.active {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.2) 0%, rgba(118, 75, 162, 0.2) 100%);
  color: #fff;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
}

.nav-item.active .nav-indicator {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 60%;
  background: linear-gradient(180deg, #667eea 0%, #764ba2 100%);
  border-radius: 0 4px 4px 0;
}

.nav-icon {
  width: 20px;
  height: 20px;
}

.nav-label {
  font-size: 0.9375rem;
  font-weight: 500;
}

.sidebar-footer {
  padding: 1.5rem;
  border-top: 1px solid rgba(102, 126, 234, 0.1);
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: rgba(102, 126, 234, 0.05);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.3s ease;
}

.user-profile:hover {
  background: rgba(102, 126, 234, 0.1);
}

.user-info {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-size: 0.875rem;
  font-weight: 600;
  color: #e5e7eb;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-email {
  font-size: 0.75rem;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.login-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.875rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 12px;
  color: white;
  font-size: 0.9375rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.login-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);
}

.btn-icon {
  width: 18px;
  height: 18px;
}

/* ===== 主内容区 ===== */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: rgba(10, 14, 39, 0.4);
  backdrop-filter: blur(10px);
  position: relative;
  min-width: 0; /* 防止flex子元素溢出 */
  overflow: hidden; /* 防止内容溢出 */
}

/* 主题切换浮动按钮 */
.theme-toggle-floating {
  position: absolute;
  top: 1.5rem;
  right: 1.5rem;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.8);
  border: 2px solid rgba(102, 126, 234, 0.3);
  border-radius: 50%;
  color: #e5e7eb;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  z-index: 100;
}

.theme-toggle-floating:hover {
  background: rgba(102, 126, 234, 0.3);
  border-color: #667eea;
  transform: scale(1.1) rotate(15deg);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);
}

.theme-toggle-floating:active {
  transform: scale(0.95) rotate(0deg);
}

.theme-toggle-floating .theme-icon {
  width: 24px;
  height: 24px;
  transition: transform 0.3s ease;
}

.theme-toggle-floating:hover .theme-icon {
  transform: rotate(15deg);
}

/* ===== 聊天视图 ===== */
.chat-view {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0; /* 防止flex子元素溢出 */
  overflow: hidden; /* 防止内容溢出 */
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden; /* 防止水平滚动 */
  padding: 2rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
  min-width: 0; /* 防止flex子元素溢出 */
}

.messages-container::-webkit-scrollbar {
  width: 6px;
}

.messages-container::-webkit-scrollbar-track {
  background: transparent;
}

.messages-container::-webkit-scrollbar-thumb {
  background: rgba(102, 126, 234, 0.3);
  border-radius: 3px;
}

.messages-container::-webkit-scrollbar-thumb:hover {
  background: rgba(102, 126, 234, 0.5);
}

/* 欢迎界面 */
.welcome-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  text-align: center;
  padding: 2rem;
}

.welcome-icon {
  width: 80px;
  height: 80px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 2rem;
  animation: float-gentle 3s ease-in-out infinite;
}

@keyframes float-gentle {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-10px); }
}

.sparkle-icon {
  width: 40px;
  height: 40px;
  color: white;
}

.welcome-title {
  font-size: 2rem;
  font-weight: 700;
  background: linear-gradient(135deg, #667eea 0%, #f093fb 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin-bottom: 1rem;
}

.welcome-desc {
  font-size: 1rem;
  color: #94a3b8;
  max-width: 500px;
  margin-bottom: 3rem;
  line-height: 1.6;
}

.suggested-questions {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1rem;
  max-width: 800px;
  width: 100%;
}

.suggestion-card {
  padding: 1.25rem;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 16px;
  color: #e5e7eb;
  cursor: pointer;
  transition: all 0.3s ease;
  text-align: left;
}

.suggestion-card:hover {
  background: rgba(102, 126, 234, 0.1);
  border-color: rgba(102, 126, 234, 0.4);
  transform: translateY(-4px);
  box-shadow: 0 12px 24px rgba(102, 126, 234, 0.2);
}

.suggestion-text {
  font-size: 0.9375rem;
  line-height: 1.5;
}

/* 搜索动画 */
.search-animation {
  display: flex;
  align-items: flex-start;
  gap: 1.5rem;
  padding: 1.5rem;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 16px;
  backdrop-filter: blur(10px);
  animation: slideIn 0.3s ease;
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.search-icon-wrapper {
  position: relative;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
}

.pulse-ring {
  position: absolute;
  inset: -8px;
  border: 2px solid #667eea;
  border-radius: 50%;
  animation: pulse-ring 1.5s ease-out infinite;
}

@keyframes pulse-ring {
  0% {
    transform: scale(0.8);
    opacity: 1;
  }
  100% {
    transform: scale(1.4);
    opacity: 0;
  }
}

.search-bot-icon {
  width: 48px;
  height: 48px;
  padding: 12px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 50%;
  color: white;
}

.search-steps {
  flex: 1;
}

.search-title {
  font-size: 1rem;
  font-weight: 600;
  color: #e5e7eb;
  margin-bottom: 1rem;
}

.search-step {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-size: 0.875rem;
  color: #94a3b8;
  margin-bottom: 0.5rem;
}

.step-dot {
  width: 8px;
  height: 8px;
  background: #667eea;
  border-radius: 50%;
  animation: blink 1.5s ease-in-out infinite;
}

@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* 消息样式 */
.message-wrapper {
  display: flex;
  gap: 1rem;
  animation: messageSlideIn 0.3s ease;
  max-width: 100%; /* 限制最大宽度 */
  min-width: 0; /* 防止flex子元素溢出 */
}

@keyframes messageSlideIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-wrapper.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.3);
}

.message-wrapper.user .message-avatar {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
  box-shadow: 0 4px 12px rgba(240, 147, 251, 0.3);
}

.avatar-icon {
  width: 24px;
  height: 24px;
  color: white;
}

.message-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-width: calc(100% - 56px); /* 减去头像宽度和gap */
  min-width: 0; /* 关键：允许内容收缩 */
}

.message-wrapper.user .message-content {
  align-items: flex-end;
}

.message-bubble {
  padding: 1.25rem;
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(102, 126, 234, 0.2);
  backdrop-filter: blur(10px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  max-width: 100%; /* 限制最大宽度 */
  min-width: 0; /* 允许收缩 */
  word-wrap: break-word; /* 强制换行 */
  word-break: break-word; /* 在单词内换行 */
  overflow-wrap: break-word; /* 现代浏览器换行 */
}

.message-bubble.user {
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.2) 0%, rgba(118, 75, 162, 0.2) 100%);
  border-color: rgba(102, 126, 234, 0.3);
}

.markdown-body {
  font-size: 0.9375rem;
  line-height: 1.7;
  color: #e5e7eb;
  max-width: 100%; /* 限制最大宽度 */
  word-wrap: break-word; /* 强制换行 */
  word-break: break-word; /* 在单词内换行 */
  overflow-wrap: break-word; /* 现代浏览器换行 */
}

/* Markdown 内容强制换行 */
.markdown-body * {
  max-width: 100%;
  word-wrap: break-word;
  word-break: break-word;
  overflow-wrap: break-word;
}

.markdown-body pre,
.markdown-body code {
  white-space: pre-wrap; /* 代码块也要换行 */
  word-break: break-all;
  overflow-x: auto;
  max-width: 100%;
}

.markdown-body p,
.markdown-body div,
.markdown-body span {
  max-width: 100%;
}

.message-time {
  font-size: 0.75rem;
  color: #64748b;
  padding: 0 0.5rem;
}

.sources-section {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid rgba(102, 126, 234, 0.1);
}

.sources-title {
  font-size: 0.75rem;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 0.75rem;
}

.sources-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.source-tag {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: rgba(102, 126, 234, 0.1);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 8px;
  font-size: 0.8125rem;
  color: #94a3b8;
  cursor: pointer;
  transition: all 0.3s ease;
}

.source-tag:hover {
  background: rgba(102, 126, 234, 0.2);
  border-color: #667eea;
  color: #e5e7eb;
  transform: translateY(-2px);
}

.source-icon {
  width: 14px;
  height: 14px;
}

/* 输入区域 */
.input-section {
  padding: 1.5rem 2rem 2rem;
  background: rgba(15, 23, 42, 0.6);
  backdrop-filter: blur(20px);
  border-top: 1px solid rgba(102, 126, 234, 0.1);
  flex-shrink: 0; /* 防止输入区域被压缩 */
}

.input-container {
  max-width: 900px;
  width: 100%; /* 确保容器宽度 */
  margin: 0 auto;
  display: flex;
  align-items: flex-end;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  background: rgba(10, 14, 39, 0.6);
  border: 2px solid rgba(102, 126, 234, 0.2);
  border-radius: 16px;
  transition: all 0.3s ease;
  box-sizing: border-box; /* 包含padding和border */
}

.input-container:focus-within {
  border-color: #667eea;
  box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.1);
}

.input-action-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: 8px;
  color: #94a3b8;
  cursor: pointer;
  transition: all 0.3s ease;
}

.input-action-btn:hover {
  background: rgba(102, 126, 234, 0.1);
  color: #e5e7eb;
}

.action-icon {
  width: 20px;
  height: 20px;
}

.message-input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  resize: none;
  font-size: 0.9375rem;
  line-height: 1.5;
  color: #e5e7eb;
  font-family: inherit;
  max-height: 120px;
}

.message-input::placeholder {
  color: #64748b;
}

.send-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(102, 126, 234, 0.2);
  border: none;
  border-radius: 8px;
  color: #94a3b8;
  cursor: pointer;
  transition: all 0.3s ease;
}

.send-btn:hover:not(:disabled) {
  background: rgba(102, 126, 234, 0.3);
  color: #e5e7eb;
}

.send-btn.active {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}

.send-btn.active:hover {
  transform: scale(1.05);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-icon {
  width: 18px;
  height: 18px;
}

/* ===== 知识库视图 ===== */
.docs-view {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 2rem;
  gap: 1.5rem;
  overflow: hidden; /* 防止内容溢出 */
  min-width: 0; /* 防止flex子元素溢出 */
}

.docs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.docs-title-section {
  flex: 1;
}

.docs-title {
  font-size: 1.75rem;
  font-weight: 700;
  background: linear-gradient(135deg, #667eea 0%, #f093fb 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin-bottom: 0.5rem;
}

.docs-subtitle {
  font-size: 0.9375rem;
  color: #94a3b8;
}

.upload-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.875rem 1.5rem;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 12px;
  color: white;
  font-size: 0.9375rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
}

.upload-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.4);
}

.docs-filters {
  display: flex;
  gap: 1rem;
}

.search-box {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 1rem;
  width: 18px;
  height: 18px;
  color: #64748b;
}

.search-input {
  width: 100%;
  padding: 0.875rem 1rem 0.875rem 3rem;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 12px;
  color: #e5e7eb;
  font-size: 0.9375rem;
  transition: all 0.3s ease;
}

.search-input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.1);
}

.search-input::placeholder {
  color: #64748b;
}

.filter-select {
  width: 160px;
}

.docs-table-container {
  flex: 1;
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 16px;
  overflow: hidden;
  backdrop-filter: blur(10px);
}

.docs-table {
  background: transparent !important;
}

.file-cell {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.file-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 8px;
  font-size: 0.625rem;
  font-weight: 700;
  color: white;
  text-transform: uppercase;
}

.file-name {
  font-size: 0.9375rem;
  color: #e5e7eb;
  font-weight: 500;
}

.delete-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: none;
  border-radius: 8px;
  color: #94a3b8;
  cursor: pointer;
  transition: all 0.3s ease;
}

.delete-btn:hover {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

.delete-icon {
  width: 16px;
  height: 16px;
}

/* ===== 对话框样式 ===== */
.custom-dialog {
  background: rgba(15, 23, 42, 0.95);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(102, 126, 234, 0.2);
  border-radius: 16px;
}

.dialog-content {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  padding: 1rem 0;
}

.code-input-group {
  display: flex;
  gap: 0.75rem;
}

.login-submit-btn {
  width: 100%;
  height: 48px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  border-radius: 12px;
  font-size: 1rem;
  font-weight: 600;
}

.upload-dialog-content {
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.upload-area {
  border-radius: 12px;
  overflow: hidden;
}

.upload-hint {
  padding: 3rem 2rem;
  text-align: center;
}

.upload-icon {
  width: 48px;
  height: 48px;
  margin: 0 auto 1rem;
  color: #667eea;
}

.upload-text {
  font-size: 1rem;
  color: #94a3b8;
  margin-bottom: 0.5rem;
}

.upload-link {
  color: #667eea;
  font-weight: 600;
}

.upload-format {
  font-size: 0.8125rem;
  color: #64748b;
}

.upload-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}

/* 来源详情抽屉 */
.source-drawer {
  background: rgba(15, 23, 42, 0.95);
  backdrop-filter: blur(20px);
}

.source-detail {
  padding: 1rem;
}

.source-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 1rem;
  background: rgba(102, 126, 234, 0.1);
  border-radius: 12px;
  margin-bottom: 1.5rem;
}

.source-icon-wrapper {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
}

.source-file-icon {
  width: 24px;
  height: 24px;
  color: white;
}

.source-info {
  flex: 1;
}

.source-filename {
  font-size: 1rem;
  font-weight: 600;
  color: #e5e7eb;
  margin-bottom: 0.25rem;
}

.source-score {
  font-size: 0.8125rem;
  color: #667eea;
  font-weight: 600;
}

.source-content {
  padding: 1rem;
}

.source-label {
  font-size: 0.75rem;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 0.75rem;
}

.source-text {
  padding: 1rem;
  background: rgba(102, 126, 234, 0.05);
  border: 1px solid rgba(102, 126, 234, 0.1);
  border-radius: 12px;
  font-size: 0.9375rem;
  line-height: 1.7;
  color: #cbd5e1;
  font-style: italic;
}

/* Element Plus 样式覆盖 */
:deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(102, 126, 234, 0.05);
  --el-table-border-color: rgba(102, 126, 234, 0.1);
  --el-table-text-color: #e5e7eb;
  --el-table-header-text-color: #94a3b8;
  color: #e5e7eb;
}

:deep(.el-table__body tr:hover > td) {
  background-color: rgba(102, 126, 234, 0.05) !important;
}

:deep(.el-input__wrapper) {
  background-color: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(102, 126, 234, 0.2);
  box-shadow: none;
}

:deep(.el-input__inner) {
  color: #e5e7eb;
}

:deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(102, 126, 234, 0.3);
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #667eea;
}

:deep(.el-button--primary) {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
}

/* 普通按钮（如获取验证码按钮） */
:deep(.el-button--default) {
  background-color: rgba(102, 126, 234, 0.15);
  border: 1px solid rgba(102, 126, 234, 0.3);
  color: #e5e7eb;
}

:deep(.el-button--default:hover) {
  background-color: rgba(102, 126, 234, 0.25);
  border-color: rgba(102, 126, 234, 0.4);
  color: #fff;
}

:deep(.el-button--default:active) {
  background-color: rgba(102, 126, 234, 0.3);
}

/* Select 下拉框 */
:deep(.el-select) {
  --el-select-input-focus-border-color: #667eea;
}

:deep(.el-select__wrapper) {
  background-color: rgba(15, 23, 42, 0.6) !important;
  border: 1px solid rgba(102, 126, 234, 0.2) !important;
  box-shadow: none !important;
}

:deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(102, 126, 234, 0.3) !important;
}

:deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px #667eea !important;
  border-color: #667eea !important;
}

:deep(.el-select__placeholder) {
  color: #64748b;
}

:deep(.el-select__caret) {
  color: #94a3b8;
}

:deep(.el-select__input) {
  color: #e5e7eb;
}

/* Select 下拉菜单 */
:deep(.el-select-dropdown) {
  background-color: rgba(15, 23, 42, 0.95);
  border: 1px solid rgba(102, 126, 234, 0.2);
  backdrop-filter: blur(20px);
}

:deep(.el-select-dropdown__item) {
  color: #e5e7eb;
}

:deep(.el-select-dropdown__item:hover) {
  background-color: rgba(102, 126, 234, 0.15);
}

:deep(.el-select-dropdown__item.is-selected) {
  color: #667eea;
  font-weight: 600;
}

:deep(.el-popper) {
  background-color: rgba(15, 23, 42, 0.95) !important;
  border: 1px solid rgba(102, 126, 234, 0.2) !important;
}

:deep(.el-dialog) {
  background: rgba(15, 23, 42, 0.95);
  border: 1px solid rgba(102, 126, 234, 0.2);
}

:deep(.el-dialog__header) {
  border-bottom: 1px solid rgba(102, 126, 234, 0.1);
}

:deep(.el-dialog__title) {
  color: #e5e7eb;
  font-weight: 600;
}

:deep(.el-drawer) {
  background: rgba(15, 23, 42, 0.95);
  backdrop-filter: blur(20px);
}

:deep(.el-drawer__header) {
  color: #e5e7eb;
  border-bottom: 1px solid rgba(102, 126, 234, 0.1);
}

:deep(.el-upload-dragger) {
  background: rgba(102, 126, 234, 0.05);
  border: 2px dashed rgba(102, 126, 234, 0.3);
}

:deep(.el-upload-dragger:hover) {
  border-color: #667eea;
}

/* Loading 加载动画 */
:deep(.el-loading-mask) {
  background-color: rgba(15, 23, 42, 0.8);
}

:deep(.el-loading-spinner .path) {
  stroke: #667eea;
}

:deep(.el-loading-text) {
  color: #e5e7eb;
}

/* Message 消息提示 */
:deep(.el-message) {
  background-color: rgba(15, 23, 42, 0.95);
  border: 1px solid rgba(102, 126, 234, 0.2);
  backdrop-filter: blur(20px);
}

:deep(.el-message__content) {
  color: #e5e7eb;
}

/* Scrollbar 滚动条 */
:deep(.el-scrollbar__thumb) {
  background-color: rgba(102, 126, 234, 0.3);
}

:deep(.el-scrollbar__thumb:hover) {
  background-color: rgba(102, 126, 234, 0.5);
}

/* Avatar 头像 */
:deep(.el-avatar) {
  background-color: rgba(102, 126, 234, 0.2);
  color: #e5e7eb;
}

/* Placeholder 文本 */
:deep(.el-input__inner::placeholder) {
  color: #64748b;
}

:deep(.el-textarea__inner::placeholder) {
  color: #64748b;
}

/* Close 按钮 */
:deep(.el-dialog__close) {
  color: #94a3b8;
}

:deep(.el-dialog__close:hover) {
  color: #e5e7eb;
}

:deep(.el-drawer__close-btn) {
  color: #94a3b8;
}

:deep(.el-drawer__close-btn:hover) {
  color: #e5e7eb;
}

/* ===== 浅色主题 ===== */
.app-container.light-theme {
  background: #f8fafc;
  color: #1e293b;
}

/* 浅色主题 - 背景层 */
.light-theme .background-layer {
  background: linear-gradient(135deg, #e0e7ff 0%, #fce7f3 100%);
}

.light-theme .gradient-orb {
  opacity: 0.3;
}

.light-theme .orb-1 {
  background: radial-gradient(circle, #818cf8 0%, rgba(129, 140, 248, 0) 70%);
}

.light-theme .orb-2 {
  background: radial-gradient(circle, #f472b6 0%, rgba(244, 114, 182, 0) 70%);
}

.light-theme .orb-3 {
  background: radial-gradient(circle, #60a5fa 0%, rgba(96, 165, 250, 0) 70%);
}

.light-theme .grid-overlay {
  background-image: 
    linear-gradient(rgba(99, 102, 241, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(99, 102, 241, 0.05) 1px, transparent 1px);
}

/* 浅色主题 - 侧边栏 */
.light-theme .sidebar {
  background: rgba(255, 255, 255, 0.8);
  border-right: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme .sidebar-header {
  border-bottom: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme .logo-icon {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  box-shadow: 0 8px 16px rgba(99, 102, 241, 0.25);
}

.light-theme .nav-item {
  color: #64748b;
}

.light-theme .nav-item:hover {
  background: rgba(99, 102, 241, 0.08);
  color: #1e293b;
}

.light-theme .nav-item.active {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.15) 0%, rgba(139, 92, 246, 0.15) 100%);
  color: #1e293b;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.15);
}

.light-theme .nav-item.active .nav-indicator {
  background: linear-gradient(180deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .sidebar-footer {
  border-top: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme .user-profile {
  background: rgba(99, 102, 241, 0.05);
}

.light-theme .user-profile:hover {
  background: rgba(99, 102, 241, 0.1);
}

.light-theme .user-name {
  color: #1e293b;
}

.light-theme .user-email {
  color: #64748b;
}

.light-theme .login-btn {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .login-btn:hover {
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);
}

/* 浅色主题 - 主内容区 */
.light-theme .main-content {
  background: rgba(248, 250, 252, 0.6);
}

/* 浅色主题 - 主题切换按钮 */
.light-theme .theme-toggle-floating {
  background: rgba(255, 255, 255, 0.9);
  border-color: rgba(99, 102, 241, 0.3);
  color: #475569;
}

.light-theme .theme-toggle-floating:hover {
  background: rgba(99, 102, 241, 0.15);
  border-color: #6366f1;
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);
}

.light-theme .welcome-icon {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .welcome-title {
  background: linear-gradient(135deg, #6366f1 0%, #ec4899 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.light-theme .welcome-desc {
  color: #64748b;
}

.light-theme .suggestion-card {
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(99, 102, 241, 0.2);
  color: #1e293b;
}

.light-theme .suggestion-card:hover {
  background: rgba(255, 255, 255, 0.95);
  border-color: rgba(99, 102, 241, 0.4);
  box-shadow: 0 12px 24px rgba(99, 102, 241, 0.15);
}

/* 浅色主题 - 搜索动画 */
.light-theme .search-animation {
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(99, 102, 241, 0.2);
}

.light-theme .pulse-ring {
  border-color: #6366f1;
}

.light-theme .search-bot-icon {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .search-title {
  color: #1e293b;
}

.light-theme .search-step {
  color: #64748b;
}

.light-theme .step-dot {
  background: #6366f1;
}

/* 浅色主题 - 消息 */
.light-theme .message-avatar {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.25);
}

.light-theme .message-wrapper.user .message-avatar {
  background: linear-gradient(135deg, #ec4899 0%, #f43f5e 100%);
  box-shadow: 0 4px 12px rgba(236, 72, 153, 0.25);
}

.light-theme .message-bubble {
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(99, 102, 241, 0.2);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}

.light-theme .message-bubble.user {
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.1) 0%, rgba(139, 92, 246, 0.1) 100%);
  border-color: rgba(99, 102, 241, 0.25);
}

.light-theme .markdown-body {
  color: #1e293b;
}

.light-theme .message-time {
  color: #94a3b8;
}

.light-theme .sources-section {
  border-top: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme .sources-title {
  color: #64748b;
}

.light-theme .source-tag {
  background: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.2);
  color: #475569;
}

.light-theme .source-tag:hover {
  background: rgba(99, 102, 241, 0.15);
  border-color: #6366f1;
  color: #1e293b;
}

/* 浅色主题 - 输入区域 */
.light-theme .input-section {
  background: rgba(255, 255, 255, 0.8);
  border-top: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme .input-container {
  background: rgba(248, 250, 252, 0.8);
  border: 2px solid rgba(99, 102, 241, 0.2);
}

.light-theme .input-container:focus-within {
  border-color: #6366f1;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.08);
}

.light-theme .input-action-btn {
  color: #64748b;
}

.light-theme .input-action-btn:hover {
  background: rgba(99, 102, 241, 0.08);
  color: #1e293b;
}

.light-theme .message-input {
  color: #1e293b;
}

.light-theme .message-input::placeholder {
  color: #94a3b8;
}

.light-theme .send-btn {
  background: rgba(99, 102, 241, 0.15);
  color: #64748b;
}

.light-theme .send-btn:hover:not(:disabled) {
  background: rgba(99, 102, 241, 0.25);
  color: #1e293b;
}

.light-theme .send-btn.active {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  color: white;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.35);
}

/* 浅色主题 - 知识库视图 */
.light-theme .docs-title {
  background: linear-gradient(135deg, #6366f1 0%, #ec4899 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.light-theme .docs-subtitle {
  color: #64748b;
}

.light-theme .upload-btn {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .upload-btn:hover {
  box-shadow: 0 8px 20px rgba(99, 102, 241, 0.3);
}

.light-theme .search-icon {
  color: #94a3b8;
}

.light-theme .search-input {
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(99, 102, 241, 0.2);
  color: #1e293b;
}

.light-theme .search-input:focus {
  border-color: #6366f1;
  box-shadow: 0 0 0 4px rgba(99, 102, 241, 0.08);
}

.light-theme .search-input::placeholder {
  color: #94a3b8;
}

.light-theme .docs-table-container {
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(99, 102, 241, 0.2);
}

.light-theme .file-icon {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .file-name {
  color: #1e293b;
}

.light-theme .delete-btn {
  color: #64748b;
}

.light-theme .delete-btn:hover {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

/* 浅色主题 - 对话框 */
.light-theme .custom-dialog {
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(99, 102, 241, 0.2);
}

.light-theme .upload-icon {
  color: #6366f1;
}

.light-theme .upload-text {
  color: #64748b;
}

.light-theme .upload-link {
  color: #6366f1;
}

.light-theme .upload-format {
  color: #94a3b8;
}

/* 浅色主题 - 来源详情抽屉 */
.light-theme .source-drawer {
  background: rgba(255, 255, 255, 0.95);
}

.light-theme .source-header {
  background: rgba(99, 102, 241, 0.08);
}

.light-theme .source-icon-wrapper {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
}

.light-theme .source-filename {
  color: #1e293b;
}

.light-theme .source-score {
  color: #6366f1;
}

.light-theme .source-label {
  color: #64748b;
}

.light-theme .source-text {
  background: rgba(99, 102, 241, 0.05);
  border: 1px solid rgba(99, 102, 241, 0.15);
  color: #475569;
}

/* 浅色主题 - Element Plus 样式覆盖 */
.light-theme :deep(.el-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: rgba(99, 102, 241, 0.05);
  --el-table-border-color: rgba(99, 102, 241, 0.15);
  --el-table-text-color: #1e293b;
  --el-table-header-text-color: #64748b;
  color: #1e293b;
}

.light-theme :deep(.el-table__body tr:hover > td) {
  background-color: rgba(99, 102, 241, 0.05) !important;
}

.light-theme :deep(.el-input__wrapper) {
  background-color: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(99, 102, 241, 0.2);
}

.light-theme :deep(.el-input__inner) {
  color: #1e293b;
}

.light-theme :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(99, 102, 241, 0.3);
}

.light-theme :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #6366f1;
}

.light-theme :deep(.el-button--primary) {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
  border: none;
}

/* 浅色主题 - 普通按钮 */
.light-theme :deep(.el-button--default) {
  background-color: rgba(99, 102, 241, 0.08);
  border: 1px solid rgba(99, 102, 241, 0.25);
  color: #475569;
}

.light-theme :deep(.el-button--default:hover) {
  background-color: rgba(99, 102, 241, 0.15);
  border-color: rgba(99, 102, 241, 0.35);
  color: #1e293b;
}

.light-theme :deep(.el-button--default:active) {
  background-color: rgba(99, 102, 241, 0.2);
}

/* 浅色主题 - Select 下拉框 */
.light-theme :deep(.el-select) {
  --el-select-input-focus-border-color: #6366f1;
}

.light-theme :deep(.el-select__wrapper) {
  background-color: rgba(255, 255, 255, 0.8) !important;
  border: 1px solid rgba(99, 102, 241, 0.2) !important;
}

.light-theme :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(99, 102, 241, 0.3) !important;
}

.light-theme :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px #6366f1 !important;
  border-color: #6366f1 !important;
}

.light-theme :deep(.el-select__placeholder) {
  color: #94a3b8;
}

.light-theme :deep(.el-select__caret) {
  color: #64748b;
}

.light-theme :deep(.el-select__input) {
  color: #1e293b;
}

/* 浅色主题 - Select 下拉菜单 */
.light-theme :deep(.el-select-dropdown) {
  background-color: rgba(255, 255, 255, 0.98);
  border: 1px solid rgba(99, 102, 241, 0.2);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
}

.light-theme :deep(.el-select-dropdown__item) {
  color: #1e293b;
}

.light-theme :deep(.el-select-dropdown__item:hover) {
  background-color: rgba(99, 102, 241, 0.08);
}

.light-theme :deep(.el-select-dropdown__item.is-selected) {
  color: #6366f1;
  font-weight: 600;
}

.light-theme :deep(.el-popper) {
  background-color: rgba(255, 255, 255, 0.98) !important;
  border: 1px solid rgba(99, 102, 241, 0.2) !important;
}

/* 浅色主题 - Loading 加载动画 */
.light-theme :deep(.el-loading-mask) {
  background-color: rgba(255, 255, 255, 0.8);
}

.light-theme :deep(.el-loading-spinner .path) {
  stroke: #6366f1;
}

.light-theme :deep(.el-loading-text) {
  color: #1e293b;
}

/* 浅色主题 - Message 消息提示 */
.light-theme :deep(.el-message) {
  background-color: rgba(255, 255, 255, 0.98);
  border: 1px solid rgba(99, 102, 241, 0.2);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.1);
}

.light-theme :deep(.el-message__content) {
  color: #1e293b;
}

/* 浅色主题 - Avatar 头像 */
.light-theme :deep(.el-avatar) {
  background-color: rgba(99, 102, 241, 0.15);
  color: #1e293b;
}

/* 浅色主题 - Placeholder 文本 */
.light-theme :deep(.el-input__inner::placeholder) {
  color: #94a3b8;
}

.light-theme :deep(.el-textarea__inner::placeholder) {
  color: #94a3b8;
}

/* 浅色主题 - Close 按钮 */
.light-theme :deep(.el-dialog__close) {
  color: #64748b;
}

.light-theme :deep(.el-dialog__close:hover) {
  color: #1e293b;
}

.light-theme :deep(.el-drawer__close-btn) {
  color: #64748b;
}

.light-theme :deep(.el-drawer__close-btn:hover) {
  color: #1e293b;
}

.light-theme :deep(.el-dialog) {
  background: rgba(255, 255, 255, 0.95);
  border: 1px solid rgba(99, 102, 241, 0.2);
}

.light-theme :deep(.el-dialog__header) {
  border-bottom: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme :deep(.el-dialog__title) {
  color: #1e293b;
  font-weight: 600;
}

.light-theme :deep(.el-drawer) {
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
}

.light-theme :deep(.el-drawer__header) {
  color: #1e293b;
  border-bottom: 1px solid rgba(99, 102, 241, 0.15);
}

.light-theme :deep(.el-upload-dragger) {
  background: rgba(99, 102, 241, 0.05);
  border: 2px dashed rgba(99, 102, 241, 0.3);
}

.light-theme :deep(.el-upload-dragger:hover) {
  border-color: #6366f1;
}

.light-theme :deep(.el-scrollbar__thumb) {
  background: rgba(99, 102, 241, 0.3);
}

.light-theme :deep(.el-scrollbar__thumb:hover) {
  background: rgba(99, 102, 241, 0.5);
}

/* 浅色主题 - 滚动条 */
.light-theme .messages-container::-webkit-scrollbar-thumb {
  background: rgba(99, 102, 241, 0.3);
}

.light-theme .messages-container::-webkit-scrollbar-thumb:hover {
  background: rgba(99, 102, 241, 0.5);
}

</style>
