<template>
  <div class="copyright-generator">
    <div class="header">
      <h1>软著生成助手</h1>
      <div class="user-info">
        
      </div>
    </div>

    <div class="content">
      
      <div class="main-title">
        <h2>一键生成软件著作权</h2>
        
      </div>

      
      <div class="task-list-section">
        <div class="section-header">
          <h3><FileTextOutlined /> 生成任务管理</h3>
          <div class="task-stats">
            <span class="stat-item">已生成：{{ completedTasks.length }}件</span>
            <span class="stat-item">待处理：{{ pendingTasks.length }}件</span>
            <span class="stat-item" v-if="generatingTasks.length > 0">生成中：{{ generatingTasks.length }}件</span>
            <span class="stat-item" v-if="errorTasks.length > 0">状态异常：{{ errorTasks.length }}件</span>
            <a-button @click="() => refreshTasks(true)" size="small" type="text">
              <ReloadOutlined />
              更新状态
            </a-button>
          </div>
        </div>

        
        <div class="task-cards">
          <div 
            v-for="(task, index) in paginatedTasks" 
            :key="task.id || `task-${index}`" 
            class="task-card"
            :class="{
              'task-generating': task.status === 'generating',
              'task-completed': task.status === 'completed',
              'task-error': task.status === 'error'
            }"
          >
            <div class="task-header">
              <h4>{{ task.name || task.appName || '未命名任务' }}</h4>
              <div class="task-status">
                <a-tag 
                  :color="getStatusColor(task.status)"
                  v-if="task.status === 'generating'"
                >
                  生成中 {{ task.progress }}%
                </a-tag>
                <a-tag 
                  :color="getStatusColor(task.status)"
                  v-else-if="task.status === 'completed'"
                >
                  已生成
                </a-tag>
                <a-tag 
                  :color="getStatusColor(task.status)"
                  v-else-if="task.status === 'error'"
                >
                  异常 !
                </a-tag>
                <a-tag v-else>待生成</a-tag>
              </div>
            </div>
            
            <div class="task-info">
              
              <div class="task-progress" v-if="task.status === 'generating'">
                <a-progress 
                  :percent="task.progress || 0" 
                  size="small"
                  :show-info="true"
                />
                <span class="progress-text">{{ task.currentStep || '准备中...' }}</span>
              </div>
              <div v-else-if="task.status === 'error'" class="error-info">
                <p style="color: #ff4d4f;">{{ task.errorMessage || '生成失败' }}</p>
              </div>
            </div>

            <div class="task-actions">
              <a-button 
                v-if="task.status === 'completed'" 
                type="primary" 
                size="small"
                @click="downloadTask(task)"
                :loading="task.downloading"
              >
                <DownloadOutlined />
                下载全部材料
              </a-button>
              <a-button 
                v-else-if="task.status === 'error'" 
                type="primary" 
                size="small"
                @click="retryTask(task)"
              >
                <ReloadOutlined />
                重新生成
              </a-button>
              <a-button 
                v-else-if="task.status !== 'generating'" 
                type="primary" 
                size="small"
                @click="startTaskGeneration(task)"
              >
                开始生成
              </a-button>
              
              
              <a-button 
                type="danger" 
                size="small"
                @click="deleteTask(task)"
                style="margin-left: 8px;"
              >
                <DeleteOutlined />
                删除
              </a-button>
            </div>
          </div>

        </div>
        
        
        <div class="pagination-wrapper" v-show="allTasks.length > pageSize">
          <Pagination
            :current="currentPage"
            :total="allTasks.length"
            :page-size="pageSize"
            :show-size-changer="false"
            :show-quick-jumper="false"
            :show-total="(total, range) => `共 ${total} 个项目`"
            @change="handlePageChange"
          />
        </div>
        
        
        <div class="add-task-section">
          <div class="task-card add-task-card" @click="showAddTaskModal = true">
            <div class="add-task-content">
              <PlusOutlined style="font-size: 24px; color: #1890ff;" />
              <p>创建新的生成任务</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    
    <a-modal
      v-model:open="showAddTaskModal"
      title=""
      @ok="createNewTask"
      @cancel="resetTaskForm"
      :ok-button-props="{ disabled: !taskForm.finalAppName }"
      width="700px"
      class="modern-task-modal"
      :footer="null"
    >
      <div class="modal-container">
        <div class="modal-header">
          <h2>创建软著生成任务</h2>
          <p>填写基本信息，系统将自动生成完整的软件著作权申请材料</p>
        </div>

        <div class="modal-body">
          <a-form :model="taskForm" layout="vertical" class="modern-form">
            
            <div class="form-group">
              <label class="form-label">项目标题 *</label>
              <a-input
                v-model:value="taskForm.finalAppName"
                placeholder="请输入项目标题"
                size="large"
                class="modern-input"
              />
            </div>

            
            <div class="form-group">
              <label class="form-label">专业领域</label>
              <a-textarea
                v-model:value="taskForm.domain"
                placeholder="如：人工智能、医疗健康、教育科技、金融服务..."
                :rows="2"
                class="modern-textarea"
              />
              <a-button 
                type="primary" 
                @click="generateSoftwareName" 
                :loading="nameGenerating"
                class="generate-btn"
              >
                智能生成名称
              </a-button>
            </div>

            
            <div class="form-group" v-if="generatedNames.length > 0">
              <label class="form-label">选择生成的名称</label>
              <div class="name-cards">
                <div 
                  v-for="(name, index) in generatedNames" 
                  :key="index" 
                  class="name-card"
                  :class="{ 'selected': taskForm.finalAppName === name }"
                  @click="taskForm.finalAppName = name"
                >
                  {{ name }}
                </div>
              </div>
            </div>

            
            <div class="form-group">
              <label class="form-label">功能描述 <span class="optional">（可选）</span></label>
              <a-textarea
                v-model:value="taskForm.appPrompt"
                placeholder="简要描述软件的主要功能和特色..."
                :rows="3"
                class="modern-textarea"
              />
            </div>
          </a-form>
        </div>

        <div class="modal-footer">
          <a-button @click="resetTaskForm" class="cancel-btn">
            取消
          </a-button>
          <a-button 
            type="primary" 
            @click="createNewTask"
            :disabled="!taskForm.finalAppName"
            class="confirm-btn"
          >
            开始生成
          </a-button>
        </div>
      </div>
    </a-modal>


  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { message, Pagination } from 'ant-design-vue'
import {
  FileTextOutlined,
  DownloadOutlined,
  PlusOutlined,
  DeleteOutlined,
} from '@ant-design/icons-vue'
import { getToken } from '/@/utils/auth'


interface GenerationStep {
  id: number
  title: string
  desc: string
  status: string
  completed: boolean
  processing: boolean
  progress: number
}


interface Task {
  id: string
  name: string
  projectName: string
  domain: string
  appName: string
  appPrompt: string
  modelId: string
  status: 'pending' | 'generating' | 'completed' | 'error'
  progress: number
  currentStep: string
  downloading: boolean
  errorMessage: string
}


const formData = reactive({
  appName: '',
  appPrompt: ''
})


const generatedNames = ref<string[]>([])
const selectedName = ref('')
const nameGenerating = ref(false)


const selectedModel = ref<string>('deepseek-chat')


const isGenerating = ref(false)
const currentStep = ref(1)


const abortController = ref<AbortController | null>(null)
const currentReader = ref<ReadableStreamDefaultReader<Uint8Array> | null>(null)


const retryCount = ref(0)
const maxRetries = 3
const isRetrying = ref(false)


const isOnline = ref(navigator.onLine)


const currentOutput = ref('')
const currentStepTitle = ref('')
const generatedData = reactive({
  appName: '',
  frontendCode: '',
  backendCode: '',
  fullCode: '',
  chapters: {
    chapter1: '',
    chapter2: '',
    chapter3: '',
    chapter4: ''
  },
  completeDocument: '',
  softwareInfo: {} as any,
  screenshots: [] as string[],
  generationStatus: {
    frontend: false,
    backend: false,
    document: false,
    info: false,
    screenshots: false,
    complete: false
  },
  fileNames: {
    frontendCode: '',
    backendCode: '',
    fullCode: '',
    chapters: {} as Record<string, string>,
    completeDocument: '',
    infoDocument: '',
    screenshotsDocument: ''
  }
})


const tasks = ref<Task[]>([])
const showAddTaskModal = ref(false)
const taskForm = reactive({
  id: '',
  name: '',
  projectName: '',  
  domain: '',
  appName: '',
  finalAppName: '',  
  appPrompt: '',
  status: 'pending' as 'pending' | 'generating' | 'completed' | 'error',
  progress: 0,
  currentStep: '',
  downloading: false,
  errorMessage: ''
})


const currentPage = ref(1)
const pageSize = ref(12) 

const allTasks = computed(() => {
  return tasks.value
})


const paginatedTasks = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return allTasks.value.slice(start, end)
})


const totalPages = computed(() => {
  return Math.ceil(allTasks.value.length / pageSize.value)
})

const completedTasks = computed(() => {
  return allTasks.value.filter(task => task.status === 'completed')
})

const generatingTasks = computed(() => {
  return allTasks.value.filter(task => task.status === 'generating')
})

const errorTasks = computed(() => {
  return allTasks.value.filter(task => task.status === 'error')
})

const pendingTasks = computed(() => {
  return allTasks.value.filter(task => task.status === 'pending')
})


const handlePageChange = (page: number) => {
  currentPage.value = page
}





const generateSoftwareName = async () => {
  if (!taskForm.domain.trim()) {
    message.error('请输入专业领域或方向')
    return
  }

  
  // if (!taskForm.modelId) {
  //   message.error('请选择一个AI模型')
  //   return
  // }

  nameGenerating.value = true
  generatedNames.value = []

  try {
    console.log('开始生成软件名称，领域:', taskForm.domain, '模型:', selectedModel.value)

    
    const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
    const response = await fetch(`${baseURL}/agenthub/api/copyright/generate-names`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Access-Token': getToken(),
      },
      body: JSON.stringify({
        domain: taskForm.domain,
        modelId: selectedModel.value 
      })
    })

    if (!response.ok) {
      throw new Error(`请求失败: ${response.status} ${response.statusText}`)
    }

    const result = await response.json()
    console.log('AI响应:', result)

    if (result && result.success && result.result && result.result.names) {
      
      const names = result.result.names
      
      if (names.length > 0) {
        generatedNames.value = names
        
        taskForm.finalAppName = names[0]
        message.success(`软件名称生成成功！生成了${names.length}个名称`)
      } else {
        message.warning('未能生成有效的软件名称，请重试')
      }
    } else {
      throw new Error(result.message || '生成失败')
    }

  } catch (error) {
    console.error('生成软件名称失败:', error)
    message.error('生成失败，请重试: ' + (error as Error).message)
  } finally {
    nameGenerating.value = false
  }
}


const goToTest = () => {
  message.info('测试功能开发中...')
}


const generateStepStream = async (stepId: number, type: string, chapterNum?: number) => {
  const step = generationSteps.value.find(s => s.id === stepId)
  if (!step) return

  currentStep.value = stepId
  step.processing = true
  currentStepTitle.value = step.title
  currentOutput.value = ''

  let response: Response

  try {
    
    abortController.value = new AbortController()

    
    if (type === 'frontend') {
      const { generateFrontendCodeStream } = await import('/@/api/copyright')
      response = await generateFrontendCodeStream({
        appName: formData.appName,
        appPrompt: formData.appPrompt,
        domain: taskForm.domain,
        modelId: selectedModel.value
      })
    } else if (type === 'backend') {
      const { generateBackendCodeStream } = await import('/@/api/copyright')
      response = await generateBackendCodeStream({
        appName: formData.appName,
        appPrompt: formData.appPrompt,
        code: generatedData.frontendCode,
        modelId: selectedModel.value
      })
    } else if (type === 'document' && chapterNum) {
      const { generateDocumentChapterStream } = await import('/@/api/copyright')
      response = await generateDocumentChapterStream({
        chapterNum,
        appName: formData.appName,
        appPrompt: formData.appPrompt,
        code: generatedData.frontendCode + '\n\n' + generatedData.backendCode,
        modelId: selectedModel.value
      })
    } else if (type === 'screenshot') {
      
      const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
      response = await fetch(`${baseURL}/agenthub/api/generate-screenshots`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          appName: formData.appName,
          appPrompt: formData.appPrompt,
          code: generatedData.frontendCode
        }),
        signal: abortController.value.signal
      })
    } else {
      throw new Error('未知的生成类型')
    }

    if (!response.ok) {
      throw new Error('网络请求失败')
    }

    const reader = response.body?.getReader()
    if (!reader) throw new Error('无法读取响应流')

    
    currentReader.value = reader

    let buffer = ''
    while (true) {
      
      if (abortController.value?.signal.aborted) {
        reader.cancel()
        throw new Error('生成已被取消')
      }

      const { done, value } = await reader.read()
      if (done) break

      const chunk = new TextDecoder().decode(value)
      buffer += chunk

      
      const lines = buffer.split('\n')
      buffer = lines.pop() || '' 

      for (const line of lines) {
        
        if (line.startsWith('data: ') || line.startsWith('data:') || (line.includes('{') && line.includes('}'))) {
          try {
            
            let jsonStr = line
            if (line.startsWith('data: ')) {
              jsonStr = line.slice(6)
            } else if (line.startsWith('data:')) {
              jsonStr = line.slice(5)
            }
            

            const data = JSON.parse(jsonStr)
            console.log('SSE数据:', data) 

            if (data.error) {
              throw new Error(data.error)
            }

            
            if (data.progress !== undefined) {
              step.progress = data.progress
              console.log(`步骤${stepId}进度更新:`, data.progress) 
            }

            
            if (data.generatedCode) {
              currentOutput.value += data.generatedCode
            } else if (data.generatedDoc) {
              currentOutput.value += data.generatedDoc
            } else if (data.content) {
              currentOutput.value += data.content
            }

            
            if (data.completed) {
              step.completed = true
              step.processing = false

              
              if (type === 'frontend') {
                if (data.fullCode || data.frontendCode) {
                  generatedData.frontendCode = data.fullCode || data.frontendCode
                  generatedData.generationStatus.frontend = true
                  console.log('前端代码已保存，长度:', generatedData.frontendCode.length)
                } else if (data.frontendCodePart1) {
                  
                  generatedData.frontendCode = data.frontendCodePart1
                  console.log('前端代码第一部分已保存，长度:', data.frontendCodePart1.length)
                } else if (data.frontendCodePart2) {
                  
                  generatedData.frontendCode += data.frontendCodePart2
                  generatedData.generationStatus.frontend = true
                  console.log('前端代码第二部分已保存，总长度:', generatedData.frontendCode.length)
                }
              } else if (type === 'backend' && (data.fullCode || data.backendCode)) {
                generatedData.backendCode = data.fullCode || data.backendCode
                generatedData.generationStatus.backend = true
                console.log('后端代码已保存，长度:', generatedData.backendCode.length)

                
                if (generatedData.frontendCode && generatedData.backendCode) {
                  generatedData.fullCode = generatedData.frontendCode + '\n\n' + generatedData.backendCode
                  console.log('完整代码已合并，长度:', generatedData.fullCode.length)
                }
              } else if (type === 'document' && data.chapterContent && chapterNum) {
                
                const chapterKey = `chapter${chapterNum}` as keyof typeof generatedData.chapters
                generatedData.chapters[chapterKey] = data.chapterContent
                console.log(`第${chapterNum}章已保存，长度:`, data.chapterContent.length)

                
                const allChaptersComplete = Object.values(generatedData.chapters).every(chapter => chapter.length > 0)
                if (allChaptersComplete) {
                  
                  generatedData.completeDocument = Object.entries(generatedData.chapters)
                    .sort(([a], [b]) => a.localeCompare(b))
                    .map(([_, content]) => content)
                    .join('\n\n')
                  generatedData.generationStatus.document = true
                  console.log('完整说明书已合并，长度:', generatedData.completeDocument.length)
                }
              } else if (type === 'screenshot' && data.screenshots) {
                
                generatedData.screenshots = data.screenshots.map((screenshot: any) => {
                  if (typeof screenshot === 'string') {
                    return screenshot
                  } else if (screenshot.fileName) {
                    
                    const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
                    return `${baseURL}/agenthub/api/download-screenshot/${screenshot.fileName}`
                  }
                  return screenshot
                })
                generatedData.generationStatus.screenshots = true
                console.log('界面截图已保存，数量:', data.screenshots.length)
              }

              
              reader.cancel()
              return
            }
          } catch (e) {
            if (e instanceof SyntaxError) {
              
              continue
            }
            throw e
          }
        } else if (line.startsWith('event: error')) {
          
          const nextLine = lines[lines.indexOf(line) + 1]
          if (nextLine && nextLine.startsWith('data: ')) {
            const errorData = JSON.parse(nextLine.slice(6))
            throw new Error(errorData.error || '生成失败')
          }
        }
      }
    }
  } catch (error) {
    step.processing = false

    
    if (error instanceof Error && error.message.includes('取消')) {
      throw error
    }

    
    if (retryCount.value < maxRetries && !abortController.value?.signal.aborted) {
      retryCount.value++
      isRetrying.value = true

      message.warning(`第${stepId}步生成失败，正在重试 (${retryCount.value}/${maxRetries})...`)

      
      await new Promise(resolve => setTimeout(resolve, 2000))

      if (!abortController.value?.signal.aborted) {
        isRetrying.value = false
        return await generateStepStream(stepId, type, chapterNum)
      }
    }

    isRetrying.value = false
    throw error
  } finally {
    
    if (currentReader.value) {
      currentReader.value = null
    }
  }
}

  
const generationSteps = ref<GenerationStep[]>([
  { id: 1, title: '生成前端代码', desc: '正在生成HTML、CSS、JavaScript代码...', status: 'pending', completed: false, processing: false, progress: 0 },
  { id: 2, title: '生成后端代码', desc: '正在生成Python Flask后端代码...', status: 'pending', completed: false, processing: false, progress: 0 },
  { id: 3, title: '生成说明书第1章', desc: '正在生成系统概述章节...', status: 'pending', completed: false, processing: false, progress: 0 },
  { id: 4, title: '生成说明书第2章', desc: '正在生成程序建立过程章节...', status: 'pending', completed: false, processing: false, progress: 0 },
  { id: 5, title: '生成说明书第3章', desc: '正在生成程序功能介绍章节...', status: 'pending', completed: false, processing: false, progress: 0 },
  { id: 6, title: '生成界面截图', desc: '正在生成软件界面截图...', status: 'pending', completed: false, processing: false, progress: 0 }
  ])

const currentGeneratingTask = ref<Task | null>(null)

const createNewTask = async () => {
  try {
    
    const finalAppName = taskForm.finalAppName || `${taskForm.domain}软件系统`
    
    console.log('最终项目标题:', finalAppName)
    
    
    const { createProject } = await import('/@/api/copyright')
    const response = await createProject({
      appName: finalAppName,
      domain: taskForm.domain,
      appPrompt: taskForm.appPrompt,
      modelId: selectedModel.value 
    })

    console.log('创建项目API响应:', response)
    console.log('响应类型:', typeof response)
    console.log('是否有success字段:', 'success' in response)
    console.log('success值:', response.success)
    
    if (response && (response.success || response.projectId)) {
      const projectData = response.result || response
      console.log('项目数据:', projectData)
      
      
      const newTask: Task = {
        id: projectData.projectId || projectData.id,
        name: finalAppName,  
        projectName: finalAppName,
        domain: taskForm.domain,
        appName: finalAppName,  
        appPrompt: taskForm.appPrompt,
        modelId: selectedModel.value, 
        status: 'pending',
        progress: 0,
        currentStep: '准备生成...',
        downloading: false,
        errorMessage: ''
      }
      
      
      tasks.value.push(newTask)
      console.log('任务已添加到列表，当前任务数量:', tasks.value.length)
      console.log('新任务对象:', newTask)
      
      showAddTaskModal.value = false
      message.success('任务创建成功！正在开始生成...')
      
      
      resetTaskForm()
      
      
      console.log('准备开始生成任务...')
      const taskInArray = tasks.value.find(t => t.id === newTask.id)
      if (taskInArray) {
        await startTaskGeneration(taskInArray)
      } else {
        console.error('未找到数组中的任务对象')
        await startTaskGeneration(newTask)
      }
      
    } else {
      message.error('创建任务失败: ' + response.message)
    }
  } catch (error) {
    console.error('创建任务失败:', error)
    message.error('创建任务失败: ' + (error as Error).message)
  }
}

const resetTaskForm = () => {
  taskForm.id = ''
  taskForm.name = ''
  taskForm.projectName = ''  
  taskForm.domain = ''
  taskForm.appName = ''
  taskForm.finalAppName = ''  
  taskForm.appPrompt = ''
  taskForm.status = 'pending'
  taskForm.progress = 0
  taskForm.currentStep = ''
  taskForm.downloading = false
  taskForm.errorMessage = ''
  
  
  generatedNames.value = []
  selectedName.value = ''
  nameGenerating.value = false
}



const startTaskGeneration = async (task: Task) => {
    if (task.status === 'generating') {
      message.info('任务已在生成中，请稍候...')
      return
    }
    
    
    console.log('启动任务生成:', task.name, '当前状态:', task.status)
    
    
    // if (task.status === 'error') {
    //   message.warning('任务状态异常，请重新生成。')
    //   return
    // }

    try {
      
      const { startProjectGeneration } = await import('/@/api/copyright')
      const response = await startProjectGeneration(task.id)

      console.log('启动生成API响应:', response)
      console.log('response.success:', response?.success)
      console.log('response.code:', response?.code)
      console.log('response.message:', response?.message)
      
      
      const isSuccess = response && (
        response.success === true || 
        response.code === 200 || 
        (response.success !== false && !response.message?.includes('失败'))
      )
      
      console.log('判断结果 isSuccess:', isSuccess)

      if (isSuccess) {
        
        task.status = 'generating'
        task.progress = 0
        task.currentStep = '正在生成...'
        task.errorMessage = ''
        
        currentGeneratingTask.value = task
        message.success('项目生成已启动')

        
        tasks.value = [...tasks.value]
        
        
        setTimeout(() => {
          refreshTasks(false)
        }, 1000)
        
        
        startProgressPolling()
        
      } else {
        task.status = 'error'
        const errorMsg = response?.message || response?.msg || '未知错误'
        task.errorMessage = errorMsg
        message.error(`项目生成启动失败: ${errorMsg}`)
        console.error('启动生成失败:', errorMsg)
      }
    } catch (error: any) {
      console.error('启动项目生成失败:', error)
      task.status = 'error'
      task.errorMessage = error.message || '启动生成失败'
      message.error(`启动项目生成失败: ${task.errorMessage}`)
    }
  }

  

// const pollTaskStatus = async (task: Task) => { ... }

  
const deleteTask = async (task: Task) => {
    try {
      
      const { Modal } = await import('ant-design-vue')
      const confirmed = await new Promise((resolve) => {
        Modal.confirm({
          title: '确认删除',
          content: `确定要删除任务"${task.name}"吗？此操作不可恢复。`,
          okText: '确定删除',
          okType: 'danger',
          cancelText: '取消',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        })
      })
      
      if (!confirmed) return
      
      const { deleteProject } = await import('/@/api/copyright')
      const response = await deleteProject(task.id)
      
    
    console.log('删除响应:', response)
    
    
    const isSuccess = response === true || 
                     response === 'success' ||
                     (response && response.success === true) ||
                     (response && response.code === 200) ||
                     (response && response.message === '删除成功') ||
                     (response && typeof response === 'string' && response.includes('成功'))
    
    if (isSuccess) {
        
        const index = tasks.value.findIndex(t => t.id === task.id)
        if (index > -1) {
          tasks.value.splice(index, 1)
        }
        message.success('任务删除成功')
      } else {
        
        const errorMessage = response?.message || response?.error || '删除失败'
        message.error(errorMessage)
      }
    } catch (error) {
      console.error('删除任务失败:', error)
      message.error('删除请求失败')
    }
    
    
    setTimeout(() => refreshTasks(false), 500) 
  }

const downloadTask = async (task: Task) => {
  if (task.status !== 'completed') {
    message.warning('请先完成任务生成。')
    return
  }
  if (task.downloading) {
    message.info('正在下载中...')
    return
  }

  task.downloading = true
  try {
    
    const token = getToken()
    const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
    const downloadUrl = `${baseURL}/agenthub/api/copyright/download-materials/${task.id}`
    
    
      const link = document.createElement('a')
    link.href = downloadUrl + (token ? `?token=${token}` : '')
         link.download = `${task.appName}-软著申请材料.zip`
    link.style.display = 'none'
    
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      
         message.success('开始下载软著申请材料压缩包')
    
  } catch (error) {
    console.error('下载失败:', error)
    message.error(`任务材料下载失败: ${(error as Error).message}`)
  } finally {
    task.downloading = false
  }
}

const retryTask = async (task: Task) => {
    if (task.status === 'generating') {
      message.warning('任务已在生成中，请稍候...')
      return
    }
    
    
    console.log('开始重新生成任务:', task.name, '当前状态:', task.status)
    
    
    await startTaskGeneration(task)
  }

  const refreshTasks = async (showSuccessMessage: boolean = false) => {
    try {
      const { getProjects } = await import('/@/api/copyright')
      const response = await getProjects()
      
      
      if (showSuccessMessage) {
        console.log('后端响应:', response)
        console.log('响应数据类型:', typeof response)
        console.log('是否为数组:', Array.isArray(response))
      }
      
      
      if (response && Array.isArray(response)) {
        
        tasks.value = response.map((project: any) => ({
          id: project.id,
          name: project.appName,
        projectName: project.projectName || project.appName,
          domain: project.domain,
          appName: project.appName,
          appPrompt: project.appPrompt || '',
          modelId: project.modelId || 'deepseek-chat',
          status: project.status,
          progress: project.progress || 0,
          currentStep: project.currentStep || '准备生成...',
          downloading: false,
          errorMessage: ''
        }))
        if (showSuccessMessage) {
          console.log('刷新任务列表成功，加载了', tasks.value.length, '个任务')
          
          
          const generatingTasks = tasks.value.filter(t => t.status === 'generating')
          if (generatingTasks.length > 0) {
            console.log('生成中的任务进度:', generatingTasks.map(t => ({
              name: t.name,
              status: t.status,
              progress: t.progress,
              currentStep: t.currentStep
            })))
          }
          
          console.log('tasks.value:', tasks.value)
          console.log('allTasks计算属性:', allTasks.value)
        }
        
        
        const newTotalPages = Math.ceil(tasks.value.length / pageSize.value)
        if (currentPage.value > newTotalPages && newTotalPages > 0) {
          currentPage.value = 1
        }
        
      } else if (response && (response.success || response.code === 200)) {
        
        const projects = response.result || response.data || []
        tasks.value = projects.map((project: any) => ({
          id: project.id,
          name: project.appName,
        projectName: project.projectName || project.appName,
          domain: project.domain,
          appName: project.appName,
          appPrompt: project.appPrompt || '',
          modelId: project.modelId || 'deepseek-chat',
          status: project.status,
          progress: project.progress || 0,
          currentStep: project.currentStep || '准备生成...',
          downloading: false,
          errorMessage: ''
        }))
        if (showSuccessMessage) {
          console.log('刷新任务列表成功（Result格式），加载了', tasks.value.length, '个任务')
        }
        
        
        const newTotalPages = Math.ceil(tasks.value.length / pageSize.value)
        if (currentPage.value > newTotalPages && newTotalPages > 0) {
          currentPage.value = 1
        }
      } else {
        console.error('获取任务列表失败:', response?.message || '响应格式错误')
        console.error('完整响应:', response)
      }
    } catch (error) {
      console.error('刷新任务列表失败:', error)
    }
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'generating':
        return 'orange'
      case 'completed':
        return 'green'
      case 'error':
        return 'red'
      default:
        return 'default'
    }
  }


let progressPollingTimer: NodeJS.Timeout | null = null

const startProgressPolling = () => {
  
  if (progressPollingTimer) {
    clearInterval(progressPollingTimer)
  }
  
  // console.log('启动进度轮询...')
  progressPollingTimer = setInterval(async () => {
    
    const generatingTasks = tasks.value.filter(t => t.status === 'generating')
    
    if (generatingTasks.length > 0) {
      
      await refreshTasks(false)
    } else {
      stopProgressPolling()
    }
  }, 3000) 
}

const stopProgressPolling = () => {
  if (progressPollingTimer) {
    // console.log('停止进度轮询')
    clearInterval(progressPollingTimer)
    progressPollingTimer = null
  }
}


onMounted(async () => {
  console.log('软著生成器页面已加载') // v2.1 - 轮询日志已清理
  
  
  stopProgressPolling()
  
  
  await refreshTasks(true) 
  
  
  const generatingTasks = tasks.value.filter(t => t.status === 'generating')
  if (generatingTasks.length > 0) {
    // console.log('发现生成中的任务，启动进度轮询，任务数量:', generatingTasks.length)
    startProgressPolling()
  }
  
  
  // startStatusPolling()
  
  
  const handleOnline = () => {
    isOnline.value = true
    if (isGenerating.value) {
      message.success('网络已恢复，生成将继续进行')
    }
    
    // startStatusPolling()
  }

  const handleOffline = () => {
    isOnline.value = false
    if (isGenerating.value) {
      message.warning('网络连接已断开，请检查网络连接')
    }
          
      stopProgressPolling()
  }

  window.addEventListener('online', handleOnline)
  window.addEventListener('offline', handleOffline)

  
  onUnmounted(() => {
    window.removeEventListener('online', handleOnline)
          window.removeEventListener('offline', handleOffline)
      stopProgressPolling()
  })
})
</script>

<style scoped>
.copyright-generator {
  min-height: 100vh;
  background: #f5f7fa;
}

.header {
  background: white;
  padding: 16px 24px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header h1 {
  margin: 0;
  color: #1890ff;
  font-size: 24px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 16px;
}

.content {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

/* 主标题样式 */
.main-title {
  text-align: center;
  margin-bottom: 40px;
  padding: 40px 0;
}

.main-title h2 {
  font-size: 32px;
  color: #1890ff;
  margin-bottom: 16px;
  font-weight: 600;
}

.subtitle {
  font-size: 16px;
  color: #666;
  margin: 0;
}

/* 卡片样式 */
.input-card, .result-card {
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  border: none;
}

.input-card .ant-card-head {
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
}

.input-card .ant-card-head-title {
  font-weight: 600;
  color: #333;
}

/* 输入区域样式 */
.input-section {
  padding: 20px 0;
}

.form-item {
  margin-bottom: 24px;
}

.form-item label {
  display: block;
  margin-bottom: 8px;
  font-weight: 500;
  color: #333;
}

.domain-input, .software-name-input, .function-input {
  border-radius: 8px;
  border: 1px solid #d9d9d9;
  transition: all 0.3s;
}

.domain-input:focus, .software-name-input:focus, .function-input:focus {
  border-color: #1890ff;
  box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.2);
}

/* 名称列表样式 */
.name-list {
  max-height: 300px;
  overflow-y: auto;
}

.name-item {
  padding: 12px 16px;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.3s;
  background: white;
}

.name-item:hover {
  border-color: #1890ff;
  background: #f0f8ff;
}

.name-item.active {
  border-color: #1890ff;
  background: #e6f7ff;
  color: #1890ff;
  font-weight: 500;
}

.empty-state {
  text-align: center;
  padding: 60px 20px;
  color: #999;
}

.empty-icon {
  font-size: 48px;
  color: #d9d9d9;
  margin-bottom: 16px;
}

/* 结果标签页样式 */
.result-tabs {
  min-height: 400px;
}

.tab-content {
  font-size: 14px;
  color: #666;
  text-align: center;
  padding: 20px;
}

.preview-content {
  padding: 20px 0;
}

.code-preview-box {
  min-height: 200px;
  margin-bottom: 20px;
}

.code-preview {
  background: #f8f9fa;
  border: 1px solid #e9ecef;
  border-radius: 8px;
  padding: 16px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  max-height: 200px;
  overflow-y: auto;
  white-space: pre-wrap;
  margin-bottom: 12px;
}

.doc-preview {
  background: #fafafa;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 16px;
  max-height: 300px;
  overflow-y: auto;
  line-height: 1.6;
}

.download-buttons {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.download-btn-item {
  width: 100%;
  height: 36px;
  border-radius: 6px;
  font-size: 14px;
}

.download-btn-main {
  width: 100%;
  height: 40px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
}

/* 生成进度样式 */
.generation-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.progress-overlay {
  background: white;
  border-radius: 16px;
  padding: 40px;
  max-width: 800px;
  width: 90%;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.3);
}

.progress-header {
  text-align: center;
  margin-bottom: 40px;
}

.progress-header h3 {
  font-size: 24px;
  color: #1890ff;
  margin-bottom: 8px;
}

.progress-header p {
  color: #666;
  margin: 0;
}

.retry-message {
  color: #fa8c16 !important;
  font-weight: 500;
}

.retry-message i {
  margin-right: 8px;
}

.steps-list {
  margin-bottom: 30px;
}

.step-item {
  display: flex;
  align-items: flex-start;
  padding: 16px;
  margin-bottom: 12px;
  border-radius: 12px;
  border: 2px solid transparent;
  transition: all 0.3s;
}

.step-item.active {
  background: #e6f7ff;
  border-color: #1890ff;
}

.step-item.completed {
  background: #f6ffed;
  border-color: #52c41a;
}

.step-item.processing {
  background: #fff7e6;
  border-color: #fa8c16;
}

.step-number {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
  font-weight: bold;
  color: white;
  background: #d9d9d9;
  flex-shrink: 0;
}

.step-item.active .step-number {
  background: #1890ff;
}

.step-item.completed .step-number {
  background: #52c41a;
}

.step-item.processing .step-number {
  background: #fa8c16;
}

.check-icon {
  color: white;
  font-size: 16px;
}

.number {
  font-size: 16px;
}

.step-info {
  flex: 1;
}

.step-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 4px;
  color: #333;
}

.step-desc {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.current-generation {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}

.generation-title {
  font-size: 16px;
  font-weight: 600;
  color: #1890ff;
  margin-bottom: 12px;
}

.generation-content {
  background: white;
  border-radius: 8px;
  padding: 16px;
  border: 1px solid #e9ecef;
}

.code-output {
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  max-height: 200px;
  overflow-y: auto;
  color: #333;
}

.progress-actions {
  text-align: center;
}

/* 结果展示样式 */
.results-header {
  text-align: center;
  margin-bottom: 40px;
  padding: 20px 0;
}

.results-header h2 {
  font-size: 28px;
  color: #52c41a;
  margin-bottom: 8px;
}

.results-header p {
  color: #666;
  margin: 0;
}

.empty-content {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 200px;
}

/* 模型选择器样式 */
.model-radio-group {
  width: 100%;
}

.model-item {
  margin-bottom: 8px;
  padding: 8px 12px;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  transition: all 0.2s;
}

.model-item:hover {
  border-color: #1890ff;
  background-color: #f0f7ff;
}

.model-item :deep(.ant-radio-wrapper) {
  width: 100%;
  font-weight: 500;
}

.models-loading {
  padding: 20px;
  text-align: center;
  color: #666;
}

/* 任务列表样式 */
.task-list-section {
  background: white;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  padding: 24px;
  margin-bottom: 24px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.section-header h3 {
  margin: 0;
  color: #333;
  font-size: 20px;
  font-weight: 600;
}

.task-stats {
  display: flex;
  gap: 20px;
  font-size: 14px;
  color: #666;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.task-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.task-card {
  background: #f8f9fa;
  border: 1px solid #e8e8e8;
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  cursor: pointer;
  transition: all 0.3s;
  position: relative;
}

.task-card:hover {
  border-color: #1890ff;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.task-card.task-generating {
  border-color: #faad14;
  background: #fffbe6;
}

.task-card.task-completed {
  border-color: #52c41a;
  background: #f6ffed;
}

.task-card.task-error {
  border-color: #f5222d;
  background: #fff1f0;
}

.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.task-header h4 {
  margin: 0;
  font-size: 18px;
  color: #333;
  font-weight: 500;
}

.task-status {
  display: flex;
  gap: 8px;
}

.task-info {
  margin-bottom: 16px;
  font-size: 14px;
  color: #666;
}

.task-progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.progress-text {
  font-size: 12px;
  color: #999;
}

.task-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.add-task-card {
  background: #e6f7ff;
  border: 1px dashed #1890ff;
  color: #1890ff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px;
  cursor: pointer;
  transition: all 0.3s;
}

.add-task-card:hover {
  background: #d0ebff;
  border-color: #597ef7;
}

.add-task-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

  .add-task-content p {
    margin: 0;
    font-size: 14px;
    color: #666;
  }

  .name-radio-group {
    width: 100%;
  }

  .name-option {
    margin-bottom: 8px;
  }

  /* 生成进度弹窗样式 */
  .generation-progress-content {
    padding: 20px 0;
  }

  .progress-header {
    text-align: center;
    margin-bottom: 30px;
  }

  .progress-header h4 {
    margin: 0 0 16px 0;
    font-size: 18px;
    color: #333;
  }

  .progress-steps {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  .step-item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px;
    border-radius: 8px;
    background: #f8f9fa;
    transition: all 0.3s;
  }

  .step-item.step-active {
    background: #e6f7ff;
    border: 1px solid #1890ff;
  }

  .step-item.step-completed {
    background: #f6ffed;
    border: 1px solid #52c41a;
  }

  .step-item.step-error {
    background: #fff1f0;
    border: 1px solid #f5222d;
  }

  .step-icon {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    font-weight: 600;
    background: #d9d9d9;
    color: #666;
  }

  .step-item.step-active .step-icon {
    background: #1890ff;
    color: white;
  }

  .step-item.step-completed .step-icon {
    background: #52c41a;
    color: white;
  }

  .step-item.step-error .step-icon {
    background: #f5222d;
    color: white;
  }

  .step-content {
    flex: 1;
  }

  .step-title {
    font-size: 14px;
    font-weight: 500;
    color: #333;
    margin-bottom: 4px;
  }

  .step-desc {
    font-size: 12px;
    color: #666;
  }

  /* 分页样式 */
  .pagination-wrapper {
    display: flex !important;
    justify-content: center;
    margin-top: 24px;
    padding: 20px 0;
    background: #fafafa;
    border-top: 1px solid #f0f0f0;
    border-radius: 0 0 8px 8px;
    visibility: visible !important;
    opacity: 1 !important;
    height: auto !important;
  }

  .pagination-wrapper .ant-pagination {
    margin: 0;
  }

  .pagination-wrapper .ant-pagination-total-text {
    color: #666;
    font-size: 14px;
    margin-right: 16px;
  }

  .pagination-wrapper .ant-pagination-item {
    border: 1px solid #d9d9d9;
    background: white;
  }

  .pagination-wrapper .ant-pagination-item-active {
    border-color: #1890ff;
    background: #1890ff;
  }

  .pagination-wrapper .ant-pagination-item-active a {
    color: white;
  }

  .pagination-wrapper .ant-pagination-prev,
  .pagination-wrapper .ant-pagination-next {
    border: 1px solid #d9d9d9;
    background: white;
  }

  .pagination-wrapper .ant-pagination-prev:hover,
  .pagination-wrapper .ant-pagination-next:hover {
    border-color: #40a9ff;
  }

  /* 添加任务区域样式 */
  .add-task-section {
    display: flex;
    justify-content: center;
    margin-top: 20px;
  }

  .add-task-section .task-card {
    max-width: 300px;
  }

  /* 现代简约对话框样式 */
  .modern-task-modal .ant-modal-content {
    border-radius: 16px;
    overflow: hidden;
    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.12);
  }

  .modern-task-modal .ant-modal-body {
    padding: 0;
  }

  .modal-container {
    background: #fff;
  }

  .modal-header {
    padding: 32px 32px 24px;
    background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
    text-align: center;
    border-bottom: 1px solid #eee;
  }

  .modal-header h2 {
    margin: 0 0 8px 0;
    font-size: 24px;
    font-weight: 600;
    color: #2c3e50;
    letter-spacing: -0.5px;
  }

  .modal-header p {
    margin: 0;
    font-size: 14px;
    color: #64748b;
    line-height: 1.5;
  }

  .modal-body {
    padding: 32px;
  }

  .form-group {
    margin-bottom: 28px;
  }

  .form-group:last-child {
    margin-bottom: 0;
  }

  .form-label {
    display: block;
    margin-bottom: 8px;
    font-size: 15px;
    font-weight: 500;
    color: #374151;
    letter-spacing: -0.2px;
  }

  .optional {
    font-weight: 400;
    color: #9ca3af;
    font-size: 13px;
  }

  .modern-input,
  .modern-textarea {
    border: 2px solid #e5e7eb;
    border-radius: 12px;
    font-size: 15px;
    transition: all 0.2s ease;
  }

  .modern-input:hover,
  .modern-textarea:hover {
    border-color: #d1d5db;
  }

  .modern-input:focus,
  .modern-textarea:focus {
    border-color: #3b82f6;
    box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
    outline: none;
  }

  .generate-btn {
    margin-top: 12px;
    height: 40px;
    border-radius: 10px;
    font-weight: 500;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    border: none;
    box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
    transition: all 0.2s ease;
  }

  .generate-btn:hover {
    transform: translateY(-1px);
    box-shadow: 0 6px 20px rgba(102, 126, 234, 0.3);
  }

  .name-cards {
    display: grid;
    gap: 12px;
    margin-top: 8px;
  }

  .name-card {
    padding: 16px 20px;
    border: 2px solid #e5e7eb;
    border-radius: 12px;
    cursor: pointer;
    transition: all 0.2s ease;
    font-size: 15px;
    color: #374151;
    background: #fff;
  }

  .name-card:hover {
    border-color: #3b82f6;
    background: #f8fafc;
  }

  .name-card.selected {
    border-color: #3b82f6;
    background: #eff6ff;
    color: #1d4ed8;
    font-weight: 500;
  }

  .modal-footer {
    padding: 24px 32px;
    background: #f8fafc;
    display: flex;
    justify-content: flex-end;
    gap: 12px;
    border-top: 1px solid #e5e7eb;
  }

  .cancel-btn {
    height: 44px;
    padding: 0 24px;
    border-radius: 10px;
    font-weight: 500;
    border: 2px solid #e5e7eb;
    color: #6b7280;
    background: #fff;
    transition: all 0.2s ease;
  }

  .cancel-btn:hover {
    border-color: #d1d5db;
    color: #374151;
  }

  .confirm-btn {
    height: 44px;
    padding: 0 32px;
    border-radius: 10px;
    font-weight: 600;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    border: none;
    box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
    transition: all 0.2s ease;
  }

  .confirm-btn:hover:not(:disabled) {
    transform: translateY(-1px);
    box-shadow: 0 6px 20px rgba(102, 126, 234, 0.3);
  }

  .confirm-btn:disabled {
    background: #e5e7eb;
    color: #9ca3af;
    box-shadow: none;
    cursor: not-allowed;
  }
</style> 