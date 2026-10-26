<template>
  <div>
    <div class="cd-toolbar">
      <el-button type="primary" :icon="Upload" @click="pickFile">上传</el-button>
      <el-button :icon="FolderAdd" @click="openCreateDir">新建文件夹</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="MagicStick" @click="router.push({ name: 'ai-center' })">AI 智能分类</el-button>
      <el-select v-model="selectedCategory" clearable placeholder="全部分类" class="category-filter">
        <el-option v-for="(label, value) in categories" :key="value" :label="label" :value="value" />
      </el-select>
      <span v-if="!isMobile" class="cd-muted">共 {{ currentEntries.length }} 项</span>
      <input ref="fileInput" type="file" hidden @change="onFileChosen" />
    </div>
    <p class="cd-muted classification-note">上传后自动分类。在「AI 智能分类」中可查看 DeepSeek 启用状态、用途摘要和分析结果。</p>

    <!-- 面包屑：后端返回扁平列表，这里按 parentId 往上回溯出路径 -->
    <el-breadcrumb v-if="!selectedCategory" separator="/" class="crumbs">
      <el-breadcrumb-item>
        <a @click="goTo(null)">全部文件</a>
      </el-breadcrumb-item>
      <el-breadcrumb-item v-for="node in breadcrumb" :key="node.id">
        <a @click="goTo(node.id)">{{ node.displayName }}</a>
      </el-breadcrumb-item>
    </el-breadcrumb>
    <p v-else class="cd-muted filter-location">正在查看所有目录中的「{{ categories[selectedCategory] }}」<el-button link type="primary" @click="selectedCategory = ''">返回当前目录</el-button></p>

    <!-- 手机：卡片列表。表格在窄屏上只能横向滚动，操作按钮基本点不到 -->
    <div v-if="isMobile" v-loading="loading" class="cd-card-list">
      <div v-if="!currentEntries.length" class="cd-empty">{{ selectedCategory ? '没有此分类的文件' : '这个目录还是空的' }}</div>

      <div v-for="row in currentEntries" :key="row.id" class="cd-card">
        <div class="cd-card-head">
          <span class="cd-card-name" @click="row.kind === 'DIRECTORY' && goTo(row.id)">
            <el-icon :color="row.kind === 'DIRECTORY' ? '#e6a23c' : '#909399'">
              <Folder v-if="row.kind === 'DIRECTORY'" />
              <Document v-else />
            </el-icon>
            <span :class="{ link: row.kind === 'DIRECTORY' }">{{ row.displayName }}</span>
          </span>

          <el-dropdown trigger="click" @command="(cmd) => onAction(cmd, row)">
            <el-button text :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="row.kind === 'DOCUMENT'" command="download">下载</el-dropdown-item>
                <el-dropdown-item v-if="row.kind === 'DOCUMENT'" command="share">分享</el-dropdown-item>
                <el-dropdown-item v-if="row.kind === 'DOCUMENT'" command="analysis">分析详情</el-dropdown-item>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <div class="cd-card-meta">
          <span>{{ row.kind === 'DIRECTORY' ? '文件夹' : categories[metadataOf(row).category] || '尚未分类' }}</span>
          <span v-if="row.kind === 'DOCUMENT'">{{ formatBytes(row.byteSize) }}</span>
          <span v-if="row.kind === 'DOCUMENT'">{{ metadataOf(row).status === 'PENDING' ? '分析中' : metadataOf(row).source ? sourceLabel(metadataOf(row).source) : '等待分类' }}</span>
        </div>
        <el-tag v-if="row.kind === 'DOCUMENT' && ['PENDING_REVIEW', 'REJECTED', 'APPROVED'].includes(metadataOf(row).reviewStatus)" :type="reviewTagType(metadataOf(row).reviewStatus)" size="small" class="file-review-tag">{{ reviewStatuses[metadataOf(row).reviewStatus] }}</el-tag>
      </div>
    </div>

    <!-- 桌面：表格 -->
    <el-table v-else v-loading="loading" :data="currentEntries" :empty-text="selectedCategory ? '没有此分类的文件' : '这个目录还是空的'" stripe>
      <el-table-column label="名称" min-width="220">
        <template #default="{ row }">
          <span v-if="row.kind === 'DIRECTORY'" class="entry-name" @click="goTo(row.id)">
            <el-icon color="#e6a23c"><Folder /></el-icon>
            {{ row.displayName }}
          </span>
          <span v-else class="entry-name plain">
            <el-icon color="#909399"><Document /></el-icon>
            {{ row.displayName }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="分类" width="170">
        <template #default="{ row }">
          <el-tag size="small" :type="row.kind === 'DIRECTORY' ? 'warning' : 'info'">
            {{ row.kind === 'DIRECTORY' ? '文件夹' : categories[metadataOf(row).category] || '尚未分类' }}
          </el-tag>
          <div v-if="row.kind === 'DOCUMENT'" class="cd-muted analysis-source">{{ metadataOf(row).status === 'PENDING' ? '分析中…' : metadataOf(row).source ? sourceLabel(metadataOf(row).source) : '等待分类' }}</div>
          <el-tag v-if="row.kind === 'DOCUMENT' && ['PENDING_REVIEW', 'REJECTED'].includes(metadataOf(row).reviewStatus)" :type="reviewTagType(metadataOf(row).reviewStatus)" size="small" class="file-review-tag">{{ reviewStatuses[metadataOf(row).reviewStatus] }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ row.kind === 'DIRECTORY' ? '-' : formatBytes(row.byteSize) }}
        </template>
      </el-table-column>

      <el-table-column label="操作" width="350" align="right">
        <template #default="{ row }">
          <el-button v-if="row.kind === 'DOCUMENT'" link type="primary" :icon="Download"
                     @click="download(row)">下载</el-button>
          <el-button v-if="row.kind === 'DOCUMENT'" link type="primary" :icon="Share"
                     @click="openShare(row)">分享</el-button>
          <el-button v-if="row.kind === 'DOCUMENT'" link type="primary" @click="openAnalysis(row)">分析详情</el-button>
          <el-button link type="primary" :icon="EditPen" @click="openRename(row)">重命名</el-button>
          <el-button link type="danger" :icon="Delete" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 上传进度 -->
    <el-dialog v-model="uploadDialog" title="正在上传" :width="dialogWidth('420px')"
               :close-on-click-modal="false" :show-close="false">
      <p class="cd-muted upload-name">{{ uploadingName }}</p>
      <el-progress :percentage="uploadPercent" :stroke-width="14" striped striped-flow />
      <p class="cd-muted upload-hint">
        请保持页面打开，上传成功后会自动整理文件分类。
      </p>
    </el-dialog>

    <!-- 新建文件夹 -->
    <el-dialog v-model="dirDialog" title="新建文件夹" :width="dialogWidth('420px')">
      <el-input v-model="dirName" placeholder="文件夹名称" maxlength="255" @keyup.enter="createDir" />
      <p class="cd-muted dialog-hint">同一目录下的文件和文件夹名称不能重复。</p>
      <template #footer>
        <el-button @click="dirDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="createDir">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameDialog" title="重命名" :width="dialogWidth('420px')">
      <el-input v-model="renameValue" placeholder="新名称" maxlength="255" @keyup.enter="submitRename" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- 创建分享 -->
    <el-dialog v-model="shareDialog" title="创建分享" :width="dialogWidth('460px')">
      <el-form :label-width="isMobile ? '70px' : '90px'">
        <el-form-item label="文件">
          <span class="share-filename">{{ shareTarget?.displayName }}</span>
        </el-form-item>
        <el-form-item label="提取码">
          <el-input v-model="sharePasscode" placeholder="4-32 位，访问者需要输入" maxlength="32" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="shareExpiresAt" type="datetime" placeholder="留空表示长期有效"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="cd-muted dialog-hint">
        请将链接与提取码发送给需要查看文件的人，可随时在「我的分享」中取消分享。
      </p>
      <template #footer>
        <el-button @click="shareDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitShare">生成链接</el-button>
      </template>
    </el-dialog>

    <!-- 分享结果 -->
    <el-dialog v-model="shareResultDialog" title="分享已创建" :width="dialogWidth('560px')">
      <el-tabs v-model="shareCopyMode">
        <el-tab-pane label="免填链接（最省事）" name="auto">
          <el-input v-model="shareAutoLink" readonly type="textarea" :rows="2" />
          <p class="cd-muted dialog-hint">
            提取码已经带在链接里，对方点开就能直接看到文件，不用再输任何东西。
            请仅发送给需要访问文件的人。
          </p>
          <el-button type="primary" class="copy-btn" :icon="CopyDocument" @click="copyAutoLink">
            一键复制免填链接
          </el-button>
        </el-tab-pane>

        <el-tab-pane label="链接 + 提取码" name="split">
          <el-input v-model="shareText" readonly type="textarea" :rows="2" />
          <p class="cd-muted dialog-hint">
            这是一整段文本，对方在「打开分享」页整段粘贴即可自动识别，
            不需要自己拆成两次粘贴。
          </p>
          <el-button type="primary" class="copy-btn" :icon="CopyDocument" @click="copyShare">
            一键复制链接和提取码
          </el-button>
        </el-tab-pane>
      </el-tabs>

      <p class="cd-muted dialog-hint">
        提取码只在这里显示一次。忘记后可到「我的分享」重置提取码，链接不变。
      </p>
    </el-dialog>
    <el-dialog v-model="analysisDialog" title="文件分类与分析" :width="dialogWidth('580px')">
      <div v-if="analysisTarget" v-loading="detailLoading">
        <h3 class="analysis-name">{{ analysisTarget.displayName }}</h3>
        <div class="analysis-tags"><el-tag>{{ categories[analysisDetails.category] || '尚未分类' }}</el-tag><el-tag type="info">{{ analysisStatuses[analysisDetails.status] || '尚未分析' }}</el-tag><el-tag v-if="analysisDetails.source" type="info">来源：{{ sourceLabel(analysisDetails.source) }}</el-tag></div>
        <p class="analysis-summary">{{ analysisDetails.summary || '暂无分析说明。可以点击重新分析以更新分类。' }}</p>
        <p v-if="analysisDetails.analyzedAt" class="cd-muted">分析时间：{{ formatTime(analysisDetails.analyzedAt) }}</p>
        <div v-if="analysisDetails.reviewStatus && analysisDetails.reviewStatus !== 'DISABLED'" class="analysis-review">
          <el-tag :type="reviewTagType(analysisDetails.reviewStatus)">{{ reviewStatuses[analysisDetails.reviewStatus] }}</el-tag>
          <p class="analysis-summary">{{ analysisDetails.reviewSummary || '等待管理员结合实际内容确认。' }}</p>
          <p v-if="analysisDetails.reviewReason" class="analysis-summary">人工复核意见：{{ analysisDetails.reviewReason }}</p>
          <p class="cd-muted">辅助审核结果仅供参考，不影响文件下载。</p>
        </div>
      </div>
      <template #footer><el-button @click="analysisDialog = false">关闭</el-button><el-button type="primary" :loading="reanalyzing === analysisTarget?.id || analysisDetails.status === 'PENDING'" :disabled="detailLoading || analysisDetails.status === 'PENDING'" @click="reanalyze(analysisTarget)">{{ analysisDetails.status === 'PENDING' ? '正在分析' : '重新分析' }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CopyDocument, Delete, Document, Download, EditPen, Folder, FolderAdd, MoreFilled,
  MagicStick, Refresh, Share, Upload
} from '@element-plus/icons-vue'
import * as fileApi from '../api/file'
import * as shareApi from '../api/share'
import * as analysisApi from '../api/analysis'
import { categories, analysisStatuses, reviewStatuses, reviewTagType, sourceLabel } from '../analysis'
import { useBreakpoint } from '../composables/useBreakpoint'
import { copyText, formatBytes, formatTime, saveBlob } from '../utils'

const emit = defineEmits(['refresh-quota'])
const router = useRouter()
const { isMobile, dialogWidth } = useBreakpoint()

const loading = ref(false)
const submitting = ref(false)
const entries = ref([])
const currentParentId = ref(null)
const selectedCategory = ref('')
const analysisById = ref({})
const analysisDialog = ref(false)
const analysisTarget = ref(null)
const detailLoading = ref(false)
const reanalyzing = ref(null)
const analysisDetails = computed(() => analysisTarget.value ? metadataOf(analysisTarget.value) : {})
let pollTimer
let disposed = false
let loadSequence = 0
const metadataOf = (row) => analysisById.value[row.id] || { status: 'NOT_ANALYZED' }

const fileInput = ref()
const uploadDialog = ref(false)
const uploadPercent = ref(0)
const uploadingName = ref('')

const dirDialog = ref(false)
const dirName = ref('')

const renameDialog = ref(false)
const renameValue = ref('')
const renameTarget = ref(null)

const shareDialog = ref(false)
const shareTarget = ref(null)
const sharePasscode = ref('')
const shareExpiresAt = ref('')
const shareResultDialog = ref(false)
const shareLink = ref('')
const shareAutoLink = ref('')
const shareText = ref('')
const shareResultPasscode = ref('')
const shareCopyMode = ref('auto')

/** 当前目录下的条目：目录排在文件前面 */
const currentEntries = computed(() => {
  const list = entries.value.filter((item) => selectedCategory.value
    ? item.kind === 'DOCUMENT' && metadataOf(item).category === selectedCategory.value
    : (item.parentId ?? null) === currentParentId.value)
  return list.sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === 'DIRECTORY' ? -1 : 1
    return a.displayName.localeCompare(b.displayName, 'zh')
  })
})

/** 沿 parentId 往上回溯出面包屑路径 */
const breadcrumb = computed(() => {
  const path = []
  let cursor = currentParentId.value
  const byId = new Map(entries.value.map((item) => [item.id, item]))
  while (cursor) {
    const node = byId.get(cursor)
    if (!node) break
    path.unshift(node)
    cursor = node.parentId ?? null
  }
  return path
})

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  try {
    const data = await fileApi.listEntries()
    if (disposed || sequence !== loadSequence) return
    entries.value = data
    if (await refreshAnalysis(data.filter((item) => item.kind === 'DOCUMENT').map((item) => item.id))) schedulePoll()
  } catch (error) {
    // 提示已在拦截器统一处理
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function goTo(id) {
  currentParentId.value = id
  selectedCategory.value = ''
}

/** 手机端下拉菜单的动作分发 */
function onAction(command, row) {
  if (command === 'download') download(row)
  else if (command === 'share') openShare(row)
  else if (command === 'rename') openRename(row)
  else if (command === 'delete') remove(row)
  else if (command === 'analysis') openAnalysis(row)
}

async function refreshAnalysis(ids) {
  const chunks = []
  for (let i = 0; i < ids.length; i += 100) chunks.push(ids.slice(i, i + 100))
  const results = await Promise.allSettled(chunks.map((chunk) => analysisApi.listAnalysis(chunk)))
  if (disposed) return
  const updates = {}
  for (const result of results) {
    if (result.status === 'fulfilled') for (const item of result.value) updates[item.entryId] = item
  }
  analysisById.value = { ...analysisById.value, ...updates }
  return results.every((result) => result.status === 'fulfilled')
}
function schedulePoll() {
  window.clearTimeout(pollTimer)
  const pendingIds = entries.value.filter((item) => metadataOf(item).status === 'PENDING').map((item) => item.id)
  if (disposed || !pendingIds.length) return
  pollTimer = window.setTimeout(async () => { if (await refreshAnalysis(pendingIds)) schedulePoll() }, 4000)
}
async function openAnalysis(row) {
  analysisTarget.value = row
  analysisDialog.value = true
  detailLoading.value = true
  try {
    const result = await analysisApi.getAnalysis(row.id)
    if (!disposed) analysisById.value = { ...analysisById.value, [row.id]: result }
    schedulePoll()
  } catch (error) { /* 请求层显示错误。 */ }
  finally { if (analysisTarget.value?.id === row.id) detailLoading.value = false }
}
async function reanalyze(row) {
  if (!row || reanalyzing.value !== null || metadataOf(row).status === 'PENDING') return
  reanalyzing.value = row.id
  try {
    const result = await analysisApi.reanalyze(row.id)
    if (disposed) return
    analysisById.value = { ...analysisById.value, [row.id]: result }
    ElMessage.success(result.status === 'PENDING' ? '已提交分析，完成后会自动更新' : '分类已更新')
    schedulePoll()
  } catch (error) { /* 请求层显示错误。 */ }
  finally { reanalyzing.value = null }
}

/* ---------- 新建文件夹 ---------- */

function openCreateDir() {
  dirName.value = ''
  dirDialog.value = true
}

async function createDir() {
  if (!dirName.value.trim()) {
    ElMessage.warning('请输入文件夹名称')
    return
  }
  submitting.value = true
  try {
    await fileApi.createEntry({
      parentId: currentParentId.value,
      displayName: dirName.value.trim(),
      kind: 'DIRECTORY',
      byteSize: null
    })
    ElMessage.success('创建成功')
    dirDialog.value = false
    await load()
  } catch (error) {
    // 409 重名等已由拦截器提示
  } finally {
    submitting.value = false
  }
}

/* ---------- 上传 ---------- */

function pickFile() {
  fileInput.value.value = ''
  fileInput.value.click()
}

async function onFileChosen(event) {
  const file = event.target.files?.[0]
  if (!file) return

  uploadingName.value = file.name
  uploadPercent.value = 0
  uploadDialog.value = true
  try {
    await fileApi.uploadFile(file, file.name, currentParentId.value, (percent) => {
      uploadPercent.value = percent
    })
    ElMessage.success('上传成功')
    await load()
    emit('refresh-quota')
  } catch (error) {
    // 配额不足、重名等已提示
  } finally {
    uploadDialog.value = false
  }
}

/* ---------- 下载 ---------- */

async function download(row) {
  try {
    const blob = await fileApi.downloadFile(row.id)
    saveBlob(blob, row.displayName)
  } catch (error) {
    // 已提示
  }
}

/* ---------- 重命名 ---------- */

function openRename(row) {
  renameTarget.value = row
  renameValue.value = row.displayName
  renameDialog.value = true
}

async function submitRename() {
  if (!renameValue.value.trim()) {
    ElMessage.warning('名称不能为空')
    return
  }
  submitting.value = true
  try {
    await fileApi.renameEntry(renameTarget.value.id, renameValue.value.trim())
    ElMessage.success('已重命名')
    renameDialog.value = false
    await load()
  } catch (error) {
    // 已提示
  } finally {
    submitting.value = false
  }
}

/* ---------- 删除 ---------- */

async function remove(row) {
  // 删目录是级联的，提示里要说清楚会连带里面的内容一起删
  const warning = row.kind === 'DIRECTORY'
    ? `确定删除文件夹「${row.displayName}」吗？它里面的所有内容会一起进回收站，可以整体恢复。`
    : `确定删除「${row.displayName}」吗？删除后会进入回收站，可以恢复。`

  const confirmed = await ElMessageBox.confirm(warning, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  }).catch(() => false)
  if (!confirmed) return

  try {
    const data = await fileApi.deleteEntry(row.id)
    const count = data?.affectedCount ?? 1
    ElMessage.success(count > 1 ? `已将 ${count} 项移入回收站` : '已移入回收站')
    await load()
  } catch (error) {
    // 已提示
  }
}

/* ---------- 分享 ---------- */

function openShare(row) {
  shareTarget.value = row
  sharePasscode.value = String(Math.floor(1000 + Math.random() * 9000))
  shareExpiresAt.value = ''
  shareDialog.value = true
}

async function submitShare() {
  if (sharePasscode.value.trim().length < 4) {
    ElMessage.warning('提取码至少 4 位')
    return
  }
  submitting.value = true
  try {
    const data = await shareApi.createShare(
      shareTarget.value.id,
      sharePasscode.value.trim(),
      shareExpiresAt.value
    )
    const code = sharePasscode.value.trim()
    shareLink.value = shareApi.shareLinkOf(data.token)
    shareAutoLink.value = shareApi.autoFillLinkOf(data.token, code)
    shareText.value = shareApi.shareTextOf(data.token, code)
    shareResultPasscode.value = code
    shareCopyMode.value = 'auto'
    shareDialog.value = false
    shareResultDialog.value = true
  } catch (error) {
    // 已提示
  } finally {
    submitting.value = false
  }
}

async function copyShare() {
  if (await copyText(shareText.value)) {
    ElMessage.success('链接和提取码已复制，直接发给对方即可')
  } else {
    ElMessage.warning('自动复制失败，请手动选中上面的内容复制')
  }
}

async function copyAutoLink() {
  if (await copyText(shareAutoLink.value)) {
    ElMessage.success('免填链接已复制，对方点开就能看到文件')
  } else {
    ElMessage.warning('自动复制失败，请手动选中上面的内容复制')
  }
}

onMounted(load)
onBeforeUnmount(() => { disposed = true; window.clearTimeout(pollTimer) })
</script>

<style scoped>
.crumbs {
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.category-filter { width: 150px; }
.classification-note { line-height: 1.7; margin: -4px 0 16px; }
.filter-location { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.analysis-source { margin-top: 5px; font-size: 12px; }
.file-review-tag { margin-top: 7px; }
.analysis-name { overflow-wrap: anywhere; margin-top: 0; }
.analysis-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.analysis-summary { line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.analysis-review { border-top: 1px solid #ebeef5; margin-top: 20px; padding-top: 18px; }
@media (max-width: 768px) { .category-filter { flex: 1 0 100%; width: 100%; } .cd-card-meta { flex-wrap: wrap; gap: 8px; } }

.crumbs a {
  cursor: pointer;
  color: #409eff;
}

.entry-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #409eff;
}

.entry-name.plain {
  cursor: default;
  color: inherit;
}

.cd-card-name .link {
  color: #409eff;
}

.share-filename {
  overflow-wrap: anywhere;
}

.upload-name {
  margin: 0 0 10px;
  word-break: break-all;
}

.copy-btn {
  margin-top: 12px;
  width: 100%;
}

.upload-hint,
.dialog-hint {
  margin: 12px 0 0;
  line-height: 1.6;
}
</style>
