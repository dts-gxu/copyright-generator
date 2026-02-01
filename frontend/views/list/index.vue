<template>
  <div class="copyright-list">
    <div class="header">
      <h2>软著生成记录</h2>
      <a-button type="primary" @click="goToGenerator">
        <PlusOutlined /> 新建生成
      </a-button>
    </div>

    <div class="search-section">
      <a-row :gutter="16">
        <a-col :span="6">
          <a-input
            v-model:value="searchParams.appName"
            placeholder="搜索软件名称"
            @press-enter="handleSearch"
          >
            <template #prefix>
              <SearchOutlined />
            </template>
          </a-input>
        </a-col>
        <a-col :span="6">
          <a-select
            v-model:value="searchParams.status"
            placeholder="生成状态"
            style="width: 100%"
            allow-clear
          >
            <a-select-option value="generating">生成中</a-select-option>
            <a-select-option value="completed">已完成</a-select-option>
            <a-select-option value="failed">生成失败</a-select-option>
          </a-select>
        </a-col>
        <a-col :span="6">
          <a-range-picker
            v-model:value="searchParams.dateRange"
            style="width: 100%"
            placeholder="创建时间"
          />
        </a-col>
        <a-col :span="6">
          <a-button type="primary" @click="handleSearch" :loading="loading">
            <SearchOutlined /> 搜索
          </a-button>
          <a-button @click="handleReset" style="margin-left: 8px;">
            重置
          </a-button>
        </a-col>
      </a-row>
    </div>

    <div class="table-section">
      <a-table
        :columns="columns"
        :data-source="dataSource"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <a-tag :color="getStatusColor(record.status)">
              {{ getStatusText(record.status) }}
            </a-tag>
          </template>
          <template v-if="column.key === 'progress'">
            <a-progress
              :percent="record.progress"
              :status="record.status === 'failed' ? 'exception' : 'normal'"
              size="small"
            />
          </template>
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button
                type="link"
                size="small"
                @click="handleView(record)"
                v-if="record.status === 'completed'"
              >
                查看
              </a-button>
              <a-button
                type="link"
                size="small"
                @click="handleDownload(record)"
                v-if="record.status === 'completed'"
              >
                下载
              </a-button>
              <a-button
                type="link"
                size="small"
                @click="handleContinue(record)"
                v-if="record.status === 'generating'"
              >
                继续
              </a-button>
              <a-button
                type="link"
                size="small"
                @click="handleRetry(record)"
                v-if="record.status === 'failed'"
              >
                重试
              </a-button>
              <a-popconfirm
                title="确定要删除这条记录吗？"
                @confirm="handleDelete(record)"
                ok-text="确定"
                cancel-text="取消"
              >
                <a-button type="link" size="small" danger>
                  删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons-vue'
import type { TableColumnsType, TableProps } from 'ant-design-vue'
import dayjs from 'dayjs'

const router = useRouter()


const searchParams = reactive({
  appName: '',
  status: undefined,
  dateRange: undefined as any,
})


const loading = ref(false)
const dataSource = ref([
  {
    id: '1',
    appName: '智能教育管理系统',
    appPrompt: '用于学校教务管理的智能化系统',
    status: 'completed',
    progress: 100,
    createTime: '2024-01-15 10:30:00',
    updateTime: '2024-01-15 11:45:00',
    generatedFiles: ['源代码.docx', '说明书.docx', '软著信息.docx'],
  },
  {
    id: '2', 
    appName: '医疗数据分析平台',
    appPrompt: '医疗大数据分析和可视化平台',
    status: 'generating',
    progress: 65,
    createTime: '2024-01-16 09:15:00',
    updateTime: '2024-01-16 09:45:00',
    generatedFiles: [],
  },
  {
    id: '3',
    appName: '金融风控系统',
    appPrompt: '基于AI的金融风险控制系统',
    status: 'failed',
    progress: 30,
    createTime: '2024-01-14 14:20:00',
    updateTime: '2024-01-14 14:35:00',
    generatedFiles: [],
  },
])


const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 3,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (total: number) => `共 ${total} 条记录`,
})


const columns: TableColumnsType = [
  {
    title: '软件名称',
    dataIndex: 'appName',
    key: 'appName',
    width: 200,
    ellipsis: true,
  },
  {
    title: '功能描述',
    dataIndex: 'appPrompt',
    key: 'appPrompt',
    width: 250,
    ellipsis: true,
  },
  {
    title: '生成状态',
    dataIndex: 'status',
    key: 'status',
    width: 100,
    align: 'center',
  },
  {
    title: '生成进度',
    dataIndex: 'progress',
    key: 'progress',
    width: 120,
    align: 'center',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    width: 150,
    sorter: true,
  },
  {
    title: '更新时间',
    dataIndex: 'updateTime',
    key: 'updateTime',
    width: 150,
    sorter: true,
  },
  {
    title: '操作',
    key: 'action',
    width: 200,
    align: 'center',
  },
]

onMounted(() => {
  loadData()
})


const loadData = async () => {
  loading.value = true
  try {
    // TODO: 实现API调用
    
    setTimeout(() => {
      loading.value = false
    }, 1000)
  } catch (error) {
    console.error('加载数据失败:', error)
    message.error('加载数据失败')
    loading.value = false
  }
}


const handleSearch = () => {
  console.log('搜索参数:', searchParams)
  loadData()
}


const handleReset = () => {
  searchParams.appName = ''
  searchParams.status = undefined
  searchParams.dateRange = undefined
  loadData()
}


const handleTableChange: TableProps['onChange'] = (pag, filters, sorter) => {
  console.log('表格变化:', pag, filters, sorter)
  pagination.current = pag?.current || 1
  pagination.pageSize = pag?.pageSize || 10
  loadData()
}


const getStatusColor = (status: string) => {
  switch (status) {
    case 'generating':
      return 'processing'
    case 'completed':
      return 'success'
    case 'failed':
      return 'error'
    default:
      return 'default'
  }
}


const getStatusText = (status: string) => {
  switch (status) {
    case 'generating':
      return '生成中'
    case 'completed':
      return '已完成'
    case 'failed':
      return '生成失败'
    default:
      return '未知'
  }
}


const handleView = (record: any) => {
  console.log('查看详情:', record)
  router.push({
    name: 'CopyrightGenerator',
    query: { id: record.id, mode: 'view' }
  })
}


const handleDownload = (record: any) => {
  console.log('下载文件:', record)
  message.info('下载功能开发中...')
}


const handleContinue = (record: any) => {
  console.log('继续生成:', record)
  router.push({
    name: 'CopyrightGenerator',
    query: { id: record.id, mode: 'continue' }
  })
}


const handleRetry = (record: any) => {
  console.log('重试生成:', record)
  router.push({
    name: 'CopyrightGenerator',
    query: { id: record.id, mode: 'retry' }
  })
}


const handleDelete = async (record: any) => {
  try {
    console.log('删除记录:', record)
    // TODO: 实现删除API调用
    message.success('删除成功')
    loadData()
  } catch (error) {
    console.error('删除失败:', error)
    message.error('删除失败')
  }
}


const goToGenerator = () => {
  router.push({ name: 'CopyrightGenerator' })
}
</script>

<style scoped>
.copyright-list {
  padding: 24px;
  background: #f5f7fa;
  min-height: 100vh;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding: 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.header h2 {
  margin: 0;
  color: #333;
  font-size: 24px;
  font-weight: 600;
}

.search-section {
  margin-bottom: 24px;
  padding: 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}

.table-section {
  background: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  padding: 20px;
}

:deep(.ant-table) {
  font-size: 14px;
}

:deep(.ant-table-thead > tr > th) {
  background: #fafafa;
  font-weight: 600;
}

:deep(.ant-table-tbody > tr:hover > td) {
  background: #f5f7fa;
}
</style> 