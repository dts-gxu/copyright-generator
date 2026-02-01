
export type CopyrightStatus = 'draft' | 'generating' | 'completed' | 'failed' | 'submitted' | 'approved' | 'rejected'


export interface CopyrightApplication {
  id: number
  appName: string
  appPrompt?: string
  status: CopyrightStatus
  progress: number
  createTime: string
  updateTime: string
  generatedFiles: string[]
  
  
  frontendCode?: string
  backendCode?: string
  fullCode?: string
  chapters?: {
    chapter1?: string
    chapter2?: string
    chapter3?: string
    chapter4?: string
  }
  completeDocument?: string
  softwareInfo?: SoftwareInfo
  screenshots?: string[]
}


export interface CreateCopyrightParams {
  appName: string
  appPrompt?: string
  domain?: string
}


export interface CodeGenerationParams {
  appName: string
  appPrompt?: string
  type: 'frontend' | 'backend' | 'full'
}


export interface CodeGenerationResult {
  code: string
  fileName: string
  language: string
}


export interface SoftwareInfo {
  name: string
  version: string
  purpose: string
  domain: string
  functions: string
  features: string
}


export interface GenerationStep {
  id: number
  title: string
  description: string
  completed: boolean
  processing: boolean
  progress: number
}


export interface GeneratedData {
  appName: string
  frontendCode: string
  backendCode: string
  fullCode: string
  chapters: {
    chapter1: string
    chapter2: string
    chapter3: string
    chapter4: string
  }
  completeDocument: string
  softwareInfo: SoftwareInfo
  screenshots: string[]
  generationStatus: {
    frontend: boolean
    backend: boolean
    document: boolean
    info: boolean
    screenshots: boolean
    complete: boolean
  }
  fileNames: {
    frontendCode: string
    backendCode: string
    fullCode: string
    chapters: Record<string, string>
    completeDocument: string
    infoDocument: string
    screenshotsDocument: string
  }
}


export interface CopyrightSearchParams {
  appName?: string
  status?: CopyrightStatus
  dateRange?: [string, string]
  current?: number
  pageSize?: number
} 