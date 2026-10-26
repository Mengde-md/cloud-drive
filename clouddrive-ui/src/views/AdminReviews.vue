<template>
  <div>
    <el-card shadow="never" class="review-settings">
      <div class="settings-row">
        <div><strong>辅助审核</strong><p class="cd-muted setting-description">默认关闭。开启后，新的文件分析会标记疑似问题，交由管理员确认。</p></div>
        <el-switch :model-value="reviewEnabled" :disabled="settingsLoading || savingSettings || !settingsReady" :loading="savingSettings" active-text="开启" inactive-text="关闭" aria-label="辅助审核开关" @change="saveSettings" />
      </div>
      <p class="cd-muted setting-description">审核结果仅用于辅助管理，不会自动删除文件或阻止下载。更改设置后，已有文件需要重新分析才会采用新设置。</p>
    </el-card>
    <div class="cd-toolbar">
      <el-select v-model="status" class="review-filter" @change="changeFilter">
        <el-option label="全部审核状态" value="" />
        <el-option v-for="(label, value) in reviewStatuses" :key="value" :label="label" :value="value" />
      </el-select>
      <el-button :icon="Refresh" @click="refresh">刷新</el-button>
      <span class="cd-muted">共 {{ total }} 项</span>
    </div>
    <div v-if="isMobile" v-loading="loading" class="cd-card-list">
      <div v-if="!items.length" class="cd-empty">当前没有符合条件的文件</div>
      <div v-for="row in items" :key="row.entryId" class="cd-card">
        <div class="cd-card-head"><strong class="cd-card-name">{{ row.fileName }}</strong><el-tag :type="reviewTagType(row.reviewStatus)" size="small">{{ reviewStatuses[row.reviewStatus] }}</el-tag></div>
        <div class="cd-card-meta"><span>{{ categories[row.category] || '其他' }}</span><span>{{ sourceLabel(row.source) }}</span></div>
        <p class="review-summary">{{ row.reviewSummary || '暂无辅助审核说明' }}</p>
        <el-button type="primary" plain size="small" @click="openReview(row)">查看并复核</el-button>
      </div>
    </div>
    <el-table v-else v-loading="loading" :data="items" stripe empty-text="当前没有符合条件的文件">
      <el-table-column prop="fileName" label="文件" min-width="200" show-overflow-tooltip />
      <el-table-column label="分类" width="100"><template #default="{ row }">{{ categories[row.category] || '其他' }}</template></el-table-column>
      <el-table-column label="审核状态" width="155"><template #default="{ row }"><el-tag size="small" :type="reviewTagType(row.reviewStatus)">{{ reviewStatuses[row.reviewStatus] }}</el-tag></template></el-table-column>
      <el-table-column label="辅助说明" min-width="260"><template #default="{ row }">{{ row.reviewSummary || '暂无辅助审核说明' }}</template></el-table-column>
      <el-table-column label="分析时间" width="170"><template #default="{ row }">{{ formatTime(row.analyzedAt) }}</template></el-table-column>
      <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link type="primary" @click="openReview(row)">查看并复核</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="total > size" class="review-pager" :layout="isMobile ? 'prev, pager, next' : 'prev, pager, next, total'" :pager-count="isMobile ? 5 : 7" :total="total" :page-size="size" :current-page="page + 1" @current-change="changePage" />
    <el-dialog v-model="reviewDialog" title="文件人工复核" :width="dialogWidth('600px')" @closed="reason = ''">
      <template v-if="target">
        <h3 class="review-name">{{ target.fileName }}</h3>
        <div class="review-tags"><el-tag>{{ categories[target.category] || '其他' }}</el-tag><el-tag :type="reviewTagType(target.reviewStatus)">{{ reviewStatuses[target.reviewStatus] }}</el-tag></div>
        <div class="review-detail"><strong>分类说明</strong><p>{{ target.summary || '暂无说明' }}</p><strong>辅助审核说明</strong><p>{{ target.reviewSummary || '暂无说明，请结合文件实际内容判断。' }}</p></div>
        <p v-if="target.reviewReason" class="cd-muted">上次复核意见：{{ target.reviewReason }}<br />复核时间：{{ formatTime(target.reviewedAt) }}</p>
        <el-form label-position="top"><el-form-item label="复核意见（必填）"><el-input v-model="reason" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="填写判断依据，方便后续处理" /></el-form-item></el-form>
        <el-alert title="请结合文件实际内容确认。标记结果不会删除文件或停止下载。" type="info" :closable="false" />
      </template>
      <template #footer><el-button @click="reviewDialog = false">取消</el-button><el-button type="danger" plain :loading="savingReview && decision === 'REJECTED'" :disabled="savingReview" @click="saveReview('REJECTED')">标记问题</el-button><el-button type="primary" :loading="savingReview && decision === 'APPROVED'" :disabled="savingReview" @click="saveReview('APPROVED')">确认通过</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as analysisApi from '../api/analysis'
import { categories, reviewStatuses, reviewTagType, sourceLabel } from '../analysis'
import { useBreakpoint } from '../composables/useBreakpoint'
import { formatTime } from '../utils'

const { isMobile, dialogWidth } = useBreakpoint()
const reviewEnabled = ref(false)
const settingsLoading = ref(false)
const settingsReady = ref(false)
const savingSettings = ref(false)
const status = ref('PENDING_REVIEW')
const loading = ref(false)
const items = ref([])
const total = ref(0)
const page = ref(0)
const size = 20
const reviewDialog = ref(false)
const target = ref(null)
const reason = ref('')
const decision = ref('')
const savingReview = ref(false)
let loadSequence = 0

async function loadSettings() {
  settingsLoading.value = true
  try { reviewEnabled.value = (await analysisApi.getAnalysisSettings()).reviewEnabled; settingsReady.value = true }
  catch (error) { /* 请求层显示错误。 */ }
  finally { settingsLoading.value = false }
}
async function saveSettings(enabled) {
  if (savingSettings.value) return
  savingSettings.value = true
  try {
    reviewEnabled.value = (await analysisApi.setAnalysisSettings(enabled)).reviewEnabled
    ElMessage.success(reviewEnabled.value ? '已开启辅助审核，将用于后续文件分析' : '已关闭辅助审核')
  } catch (error) { /* 保留原有开关状态。 */ }
  finally { savingSettings.value = false }
}
async function load() {
  const sequence = ++loadSequence
  loading.value = true
  try {
    const data = await analysisApi.listReviews(status.value, page.value, size)
    if (sequence !== loadSequence) return
    items.value = data.items
    total.value = data.total
  } catch (error) { /* 请求层显示错误。 */ }
  finally { if (sequence === loadSequence) loading.value = false }
}
function changeFilter() { page.value = 0; load() }
function changePage(current) { page.value = current - 1; load() }
function openReview(row) { target.value = row; reason.value = ''; reviewDialog.value = true }
async function saveReview(value) {
  if (savingReview.value || !target.value) return
  if (!reason.value.trim()) { ElMessage.warning('请填写复核意见'); return }
  savingReview.value = true
  decision.value = value
  try {
    await analysisApi.reviewFile(target.value.entryId, value, reason.value.trim())
    ElMessage.success(value === 'APPROVED' ? '已人工确认通过' : '已标记问题')
    reviewDialog.value = false
    await load()
    if (!items.value.length && page.value > 0) { page.value -= 1; await load() }
  } catch (error) { /* 请求层显示错误。 */ }
  finally { savingReview.value = false }
}
function refresh() { loadSettings(); load() }
onMounted(refresh)
</script>

<style scoped>
.review-settings { margin-bottom: 20px; }
.settings-row { display: flex; align-items: center; justify-content: space-between; gap: 24px; }
.setting-description { margin: 8px 0 0; line-height: 1.7; }
.review-filter { width: 190px; }
.review-pager { margin-top: 16px; justify-content: flex-end; }
.review-summary, .review-detail p { line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; }
.review-name { margin: 0 0 12px; overflow-wrap: anywhere; }
.review-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.review-detail { margin-bottom: 20px; }
@media (max-width: 768px) { .settings-row { align-items: flex-start; flex-direction: column; gap: 12px; } .review-filter { flex: 1; } }
</style>
