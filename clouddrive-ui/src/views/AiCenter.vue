<template>
  <div class="ai-center">
    <el-card shadow="never" class="ai-intro">
      <div class="ai-heading">
        <div class="ai-heading-copy">
          <h1><el-icon><MagicStick /></el-icon>AI 智能分类</h1>
          <p>集中查看文件类别、内容摘要与辅助审核结果。</p>
        </div>
        <el-tag v-if="capabilities" :type="capabilities.aiEnabled ? 'success' : 'warning'" size="large">{{ capabilities.aiEnabled ? 'DeepSeek 已启用' : 'DeepSeek 未启用' }}</el-tag>
        <el-tag v-else type="info">{{ loading ? '正在读取状态' : '暂未获取启用状态' }}</el-tag>
      </div>
      <el-alert v-if="capabilities && !capabilities.aiEnabled" type="warning" :closable="false" show-icon class="ai-notice">
        <template #title>当前使用文件后缀分类，尚未启用 AI 内容分析</template>
        <p>{{ userStore.isAdmin ? '请在服务端私有配置中填写 DeepSeek API Key 并重启服务。启用后可分析文本与代码内容，生成用途摘要。' : '管理员启用 DeepSeek 后，可对文本与代码进行内容分析。当前仍可按文件后缀查看和整理类别。' }}</p>
      </el-alert>
      <p class="ai-scope">文本、Markdown、CSV 和代码支持有限内容分析；图片、音视频、压缩包及尚不支持提取的文档按后缀分类。</p>
      <div class="ai-heading-actions">
        <el-button type="primary" :icon="Upload" @click="router.push({ name: 'files' })">前往上传文件</el-button>
        <el-button v-if="userStore.isAdmin" :icon="UserFilled" @click="router.push({ name: 'admin-users' })">管理员控制台</el-button>
        <span v-if="capabilities" class="cd-muted">辅助审核{{ capabilities.reviewEnabled ? '已开启，疑似问题交由管理员复核' : '默认关闭，可由管理员开启' }}</span>
      </div>
    </el-card>

    <div class="ai-stats">
      <div><strong>{{ files.length }}</strong><span>我的文件</span></div>
      <div><strong>{{ aiCount }}</strong><span>已用 AI 分析</span></div>
      <div><strong>{{ suffixCount }}</strong><span>按后缀分类</span></div>
      <div><strong>{{ pendingCount }}</strong><span>正在分析</span></div>
    </div>

    <div class="cd-toolbar ai-filters">
      <el-input v-model="query" placeholder="搜索文件名称" clearable class="ai-search" />
      <el-select v-model="category" clearable placeholder="全部类别" class="ai-filter">
        <el-option v-for="(label, value) in categories" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="source" clearable placeholder="全部分析来源" class="ai-filter">
        <el-option label="DeepSeek AI" value="DEEPSEEK" /><el-option label="文件后缀" value="EXTENSION" />
      </el-select>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="isMobile" v-loading="loading" class="cd-card-list">
      <div v-if="!visibleFiles.length" class="cd-empty">{{ files.length ? '没有符合条件的文件' : '上传文件后，这里会展示分类结果' }}</div>
      <div v-for="row in visibleFiles" :key="row.id" class="cd-card">
        <div class="cd-card-head"><strong class="cd-card-name">{{ row.displayName }}</strong><el-tag size="small">{{ categories[row.category] || '待分类' }}</el-tag></div>
        <div class="cd-card-meta"><span>{{ sourceText(row.source) }}</span><span>{{ analysisStatuses[row.status] || '尚未分析' }}</span></div>
        <p class="ai-summary">{{ row.summary || '尚未分析，可点击下方按钮更新分类。' }}</p>
        <div class="ai-row-actions"><el-button size="small" @click="openDetails(row)">查看详情</el-button><el-button type="primary" plain size="small" :loading="analyzing === row.id || row.status === 'PENDING'" :disabled="analyzing !== null || row.status === 'PENDING'" @click="analyze(row)">{{ analyzeLabel }}</el-button></div>
      </div>
    </div>
    <el-table v-else v-loading="loading" :data="visibleFiles" stripe :empty-text="files.length ? '没有符合条件的文件' : '上传文件后，这里会展示分类结果'">
      <el-table-column prop="displayName" label="文件" min-width="180" show-overflow-tooltip />
      <el-table-column label="类别" width="105"><template #default="{ row }"><el-tag size="small">{{ categories[row.category] || '待分类' }}</el-tag></template></el-table-column>
      <el-table-column label="分析来源" width="125"><template #default="{ row }"><el-tag size="small" :type="row.source === 'DEEPSEEK' ? 'success' : 'info'">{{ sourceText(row.source) }}</el-tag></template></el-table-column>
      <el-table-column label="分析状态" width="115"><template #default="{ row }">{{ analysisStatuses[row.status] || '尚未分析' }}</template></el-table-column>
      <el-table-column label="用途摘要 / 分类说明" min-width="230" show-overflow-tooltip><template #default="{ row }">{{ row.summary || '尚未分析' }}</template></el-table-column>
      <el-table-column label="操作" width="220" align="right"><template #default="{ row }"><el-button link type="primary" @click="openDetails(row)">查看详情</el-button><el-button link type="primary" :loading="analyzing === row.id || row.status === 'PENDING'" :disabled="analyzing !== null || row.status === 'PENDING'" @click="analyze(row)">{{ analyzeLabel }}</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="detailDialog" title="文件分析详情" :width="dialogWidth('600px')">
      <template v-if="detail">
        <h3 class="ai-detail-name">{{ detail.displayName }}</h3>
        <div class="ai-detail-tags"><el-tag>{{ categories[detail.category] || '待分类' }}</el-tag><el-tag type="info">{{ sourceText(detail.source) }}</el-tag><el-tag type="info">{{ analysisStatuses[detail.status] || '尚未分析' }}</el-tag></div>
        <p class="ai-summary">{{ detail.summary || '尚未生成分析说明。' }}</p>
        <p class="cd-muted">分析时间：{{ formatTime(detail.analyzedAt) }}</p>
        <div v-if="detail.reviewStatus && detail.reviewStatus !== 'DISABLED'" class="ai-detail-review">
          <el-tag :type="reviewTagType(detail.reviewStatus)">{{ reviewStatuses[detail.reviewStatus] }}</el-tag>
          <p class="ai-summary">{{ detail.reviewSummary }}</p>
          <p v-if="detail.reviewReason" class="ai-summary">人工复核意见：{{ detail.reviewReason }}</p>
        </div>
      </template>
      <template #footer><el-button @click="detailDialog = false">关闭</el-button><el-button type="primary" :loading="analyzing === detail?.id || detail?.status === 'PENDING'" :disabled="!detail || analyzing !== null || detail?.status === 'PENDING'" @click="analyze(detail)">{{ analyzeLabel }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Refresh, Upload, UserFilled } from '@element-plus/icons-vue'
import * as fileApi from '../api/file'
import * as analysisApi from '../api/analysis'
import { categories, analysisStatuses, reviewStatuses, reviewTagType } from '../analysis'
import { useUserStore } from '../stores/user'
import { useBreakpoint } from '../composables/useBreakpoint'
import { formatTime } from '../utils'

const router = useRouter()
const userStore = useUserStore()
const { isMobile, dialogWidth } = useBreakpoint()
const capabilities = ref(null)
const entries = ref([])
const metadata = ref({})
const loading = ref(false)
const analyzing = ref(null)
const query = ref('')
const category = ref('')
const source = ref('')
const detailId = ref(null)
const detailDialog = ref(false)
let sequence = 0
let disposed = false
let pollTimer
const files = computed(() => entries.value.map(row => ({ ...row, ...metadata.value[row.id] })))
const visibleFiles = computed(() => files.value.filter(row => (!query.value.trim() || row.displayName.toLowerCase().includes(query.value.trim().toLowerCase())) && (!category.value || row.category === category.value) && (!source.value || row.source === source.value)))
const aiCount = computed(() => files.value.filter(row => row.source === 'DEEPSEEK' && row.status === 'COMPLETED').length)
const suffixCount = computed(() => files.value.filter(row => row.source === 'EXTENSION' && row.status !== 'PENDING').length)
const pendingCount = computed(() => files.value.filter(row => row.status === 'PENDING').length)
const detail = computed(() => files.value.find(row => row.id === detailId.value))
const analyzeLabel = computed(() => capabilities.value?.aiEnabled ? '重新 AI 分析' : '更新后缀分类')
const sourceText = value => value === 'DEEPSEEK' ? 'DeepSeek AI' : value === 'EXTENSION' ? '文件后缀' : '待分析'

async function fetchMetadata(ids) {
  const chunks = []
  for (let i = 0; i < ids.length; i += 100) chunks.push(ids.slice(i, i + 100))
  const results = await Promise.all(chunks.map(chunk => analysisApi.listAnalysis(chunk)))
  return Object.fromEntries(results.flat().map(row => [row.entryId, row]))
}
async function load() {
  const current = ++sequence
  loading.value = true
  window.clearTimeout(pollTimer)
  try {
    const [allEntries, state] = await Promise.all([fileApi.listEntries(), analysisApi.getCapabilities()])
    const documents = allEntries.filter(row => row.kind === 'DOCUMENT')
    const results = await fetchMetadata(documents.map(row => row.id))
    if (disposed || current !== sequence) return
    entries.value = documents
    capabilities.value = state
    metadata.value = results
    schedulePoll()
  } catch (error) { /* 由请求层展示错误，保留已读取结果。 */ }
  finally { if (current === sequence) loading.value = false }
}
function schedulePoll() {
  window.clearTimeout(pollTimer)
  const ids = files.value.filter(row => row.status === 'PENDING').map(row => row.id)
  if (disposed || !ids.length) return
  const current = sequence
  pollTimer = window.setTimeout(async () => {
    try {
      const results = await fetchMetadata(ids)
      if (disposed || current !== sequence) return
      metadata.value = { ...metadata.value, ...results }
      schedulePoll()
    } catch (error) { /* 可手动刷新，不持续重试失败请求。 */ }
  }, 3000)
}
function openDetails(row) { detailId.value = row.id; detailDialog.value = true }
async function analyze(row) {
  if (!row || analyzing.value !== null || row.status === 'PENDING') return
  analyzing.value = row.id
  try {
    const result = await analysisApi.reanalyze(row.id)
    if (disposed) return
    metadata.value = { ...metadata.value, [row.id]: result }
    ElMessage.success(result.status === 'PENDING' ? '已提交分析，结果会自动更新' : '分类已更新')
    schedulePoll()
  } catch (error) { /* 由请求层展示错误。 */ }
  finally { analyzing.value = null }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; sequence++; window.clearTimeout(pollTimer) })
</script>

<style scoped>
.ai-intro { margin-bottom: 18px; }
.ai-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.ai-heading h1 { display: flex; align-items: center; gap: 10px; margin: 0; font-size: 22px; color: #303133; }
.ai-heading h1 .el-icon { color: #409eff; }
.ai-heading-copy p { margin: 8px 0 0; font-size: 14px; color: #606266; line-height: 1.7; }
.ai-notice { margin-top: 18px; }
.ai-notice p { margin: 6px 0 0; line-height: 1.7; }
.ai-scope { color: #909399; font-size: 13px; line-height: 1.8; margin: 14px 0; }
.ai-heading-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.ai-heading-actions .el-button { margin-left: 0; }
.ai-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
.ai-stats > div { background: #fff; border: 1px solid #e4e7ed; border-radius: 8px; padding: 18px; }
.ai-stats strong { display: block; color: #303133; font-size: 26px; margin-bottom: 6px; }
.ai-stats span { color: #909399; font-size: 13px; }
.ai-search { width: 260px; }
.ai-filter { width: 170px; }
.ai-summary { font-size: 14px; color: #606266; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.ai-row-actions, .ai-detail-tags { display: flex; gap: 8px; flex-wrap: wrap; }
.ai-row-actions .el-button { margin-left: 0; }
.ai-detail-name { margin: 0 0 14px; overflow-wrap: anywhere; }
.ai-detail-review { border-top: 1px solid #ebeef5; margin-top: 20px; padding-top: 18px; }
@media(max-width: 768px) { .ai-heading { flex-direction: column; gap: 12px; } .ai-heading h1 { font-size: 20px; } .ai-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; } .ai-stats > div { padding: 14px; } .ai-search { width: 100%; } .ai-filter { flex: 1; min-width: 0; } }
</style>
