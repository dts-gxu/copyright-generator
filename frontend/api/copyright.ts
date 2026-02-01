import { defHttp } from '/@/utils/http/axios'
import type { 
  CopyrightApplication, 
  CreateCopyrightParams,
  CodeGenerationParams,
  CodeGenerationResult
} from '/@/types/copyright'

export function getCopyrightList(params: any) {
  return defHttp.get({ url: '/copyright/list', params })
}

export function getCopyrightDetail(id: number) {
  return defHttp.get({ url: `/copyright/${id}` })
}

export function createCopyright(params: CreateCopyrightParams) {
  return defHttp.post({ url: '/copyright/create', params })
}

export function updateCopyright(id: number, params: Partial<CreateCopyrightParams>) {
  return defHttp.put({ url: `/copyright/${id}`, params })
}

export function deleteCopyright(id: number) {
  return defHttp.delete({ url: `/copyright/${id}` })
}

export function submitCopyright(id: number) {
  return defHttp.post({ url: `/copyright/${id}/submit` })
}

export function generateCode(params: CodeGenerationParams) {
  return defHttp.post({ url: '/copyright/generate-code', params })
}

export function generateDocument(params: {
  applicationId: number
  type: string
}) {
  return defHttp.post({ url: '/copyright/generate-document', params })
}

export function createProject(params: {
  appName: string
  domain: string
  appPrompt: string
  modelId: string
}) {
  return defHttp.post({ url: '/agenthub/api/copyright/projects', params })
}

export function getProjects() {
  return defHttp.get({ url: '/agenthub/api/copyright/projects' })
}

export function startProjectGeneration(projectId: string) {
  return defHttp.post({ url: `/agenthub/api/copyright/projects/${projectId}/generate` })
}

export function getProjectStatus(projectId: string) {
  return defHttp.get({ url: `/agenthub/api/copyright/projects/${projectId}/status` })
}

export function deleteProject(projectId: string) {
  return defHttp.delete({ url: `/agenthub/api/copyright/projects/${projectId}` })
}

export function uploadDocument(file: File, applicationId: number, type: string) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('applicationId', applicationId.toString())
  formData.append('type', type)

  return defHttp.uploadFile(
    { url: '/copyright/upload-document' },
    { file: formData },
  )
}

export function generateSoftwareName(domain: string) {
  return defHttp.post({ url: '/api/generate-software-names', params: { domain } })
}

export function generateSoftwareNameStream(domain: string, modelId?: string): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  
  const prompt = `请基于'${domain}'这个领域或专业方向，生成10个适合软件著作权申请的软件名称，名字要长要专业一点，名称必须以软件、系统、平台结尾。只需要告诉我名称就行，不用告诉我其他东西，不用标序号！不可以标序号。每个名称单独一行。`
  
  return fetch(`${baseURL}/agenthub/api/v1/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: modelId || "deepseek-chat",
      messages: [
        {
          role: "user",
          content: prompt
        }
      ],
      stream: true,
      apiCode: "MAXGPT",
      max_tokens: 2000,
      temperature: 0.8
    })
  })
}

export function extractSoftwareInfo(params: {
  chapter1: string
  modelId?: string
}): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  return fetch(`${baseURL}/agenthub/api/extract-software-info`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params)
  })
}

export function saveSoftwareInfoToWord(params: {
  appName: string
  info: any
}) {
  return defHttp.post({ url: '/save-software-info-to-word', params })
}

export function generateParallel(params: {
  appName: string
  frontendCode: string
  appPrompt?: string
  modelId?: string
}): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  return fetch(`${baseURL}/agenthub/api/generate-parallel`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params)
  })
}

export function downloadManual(params: {
  appName: string
  chapters: string[]
}): Promise<Blob> {
  return defHttp.post(
    { url: '/api/download/manual', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function downloadManualWithScreenshots(params: {
  appName: string
  appPrompt?: string
  frontendCode?: string
  backendCode?: string
  chapters: string[]
  screenshotPaths: string[]
}): Promise<Blob> {
  return defHttp.post(
    { url: '/api/download/manual-with-screenshots', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function downloadCode(params: {
  appName: string
  frontendCode?: string
  backendCode?: string
}): Promise<Blob> {
  return defHttp.post(
    { url: '/api/download/code', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function downloadSoftwareInfo(params: {
  appName: string
  appPrompt?: string
  frontendCode?: string
  backendCode?: string
  chapters?: any[]
}): Promise<Blob> {
  return defHttp.post(
    { url: '/agenthub/api/download/info', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function downloadAllMaterials(params: {
  appName: string
  appPrompt: string
  frontendCode?: string
  backendCode?: string
  chapters?: string[]
}): Promise<Blob> {
  return defHttp.post(
    { url: '/agenthub/api/download/all', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function downloadTest(params: {
  appName: string
}): Promise<Blob> {
  return defHttp.post(
    { url: '/api/download/test', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function testHeaderFooter(params: {
  appName: string
}): Promise<Blob> {
  return defHttp.post(
    { url: '/api/test/header-footer', params, responseType: 'blob' },
    { isReturnNativeResponse: true }
  ).then(response => response.data)
}

export function generateFrontendCodeStream(params: {
  appName: string
  appPrompt?: string
  domain?: string
  modelId?: string
}): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  return fetch(`${baseURL}/agenthub/api/generate-frontend-code`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params)
  })
}

export function generateBackendCodeStream(params: {
  appName: string
  appPrompt?: string
  code?: string
  modelId?: string
}): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  return fetch(`${baseURL}/agenthub/api/generate-backend-code`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params)
  })
}

export function generateDocumentChapterStream(params: {
  chapterNum: number
  appName: string
  appPrompt?: string
  code?: string
  modelId?: string
}): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  return fetch(`${baseURL}/agenthub/api/generate-document-chapter${params.chapterNum}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params)
  })
}

export function generateAllStream(params: {
  appName: string
  appPrompt?: string
  domain?: string
}): Promise<Response> {
  const baseURL = import.meta.env.VITE_GLOB_API_URL || 'http://localhost:8082'
  return fetch(`${baseURL}/agenthub/api/generate-all-stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params)
  })
} 