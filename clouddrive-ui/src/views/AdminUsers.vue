<template>
  <div>
    <div class="cd-toolbar">
      <el-input v-model="query" clearable placeholder="搜索用户名或邮箱" class="user-search" @keyup.enter="search" @clear="search" />
      <el-button type="primary" :icon="Search" @click="search">搜索</el-button>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <span class="cd-muted">共 {{ total }} 个账号</span>
    </div>
    <el-alert title="可协助用户重置密码、扩容、管理账号状态与管理员身份。扩容只提高账号配额，不增加服务器磁盘。" type="info" :closable="false" show-icon class="admin-note" />
    <div v-if="isMobile" v-loading="loading" class="cd-card-list">
      <div v-if="!items.length" class="cd-empty">没有找到账号</div>
      <div v-for="row in items" :key="row.id" class="cd-card">
        <div class="cd-card-head">
          <strong class="cd-card-name">{{ row.username }}<small v-if="row.id === userStore.id">（当前账号）</small></strong>
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">{{ row.enabled ? '正常' : '已禁用' }}</el-tag>
        </div>
        <div class="user-email">{{ row.email || '未绑定邮箱' }}</div>
        <div class="cd-card-meta"><el-tag size="small" :type="row.role === 'ADMIN' ? 'primary' : 'info'">{{ roleLabel(row.role) }}</el-tag><span>总容量 {{ formatBytes(row.quotaBytes) }}</span></div>
        <div class="user-actions">
          <el-button size="small" :disabled="accountBusy" @click="openPassword(row)">重置密码</el-button>
          <el-button size="small" :disabled="accountBusy || row.quotaBytes >= MAX_QUOTA_BYTES" @click="openQuota(row)">扩容</el-button>
          <el-button size="small" :disabled="accountBusy" @click="changeRole(row)">{{ row.role === 'ADMIN' ? '设为普通用户' : '设为管理员' }}</el-button>
          <el-button size="small" :type="row.enabled ? 'danger' : 'success'" :disabled="accountBusy || row.id === userStore.id" @click="changeStatus(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button>
        </div>
      </div>
    </div>
    <el-table v-else v-loading="loading" :data="items" stripe empty-text="没有找到账号">
      <el-table-column label="用户名" min-width="140"><template #default="{ row }">{{ row.username }}<span v-if="row.id === userStore.id" class="cd-muted">（当前账号）</span></template></el-table-column>
      <el-table-column label="邮箱" min-width="180"><template #default="{ row }">{{ row.email || '未绑定邮箱' }}</template></el-table-column>
      <el-table-column label="身份" width="110"><template #default="{ row }"><el-tag size="small" :type="row.role === 'ADMIN' ? 'primary' : 'info'">{{ roleLabel(row.role) }}</el-tag></template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag size="small" :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '正常' : '已禁用' }}</el-tag></template></el-table-column>
      <el-table-column label="总容量" width="110"><template #default="{ row }">{{ formatBytes(row.quotaBytes) }}</template></el-table-column>
      <el-table-column label="注册时间" width="155"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="340" align="right"><template #default="{ row }">
        <el-button link type="primary" :disabled="accountBusy" @click="openPassword(row)">重置密码</el-button>
        <el-button link type="primary" :disabled="accountBusy || row.quotaBytes >= MAX_QUOTA_BYTES" @click="openQuota(row)">扩容</el-button>
        <el-button link type="primary" :disabled="accountBusy" @click="changeRole(row)">{{ row.role === 'ADMIN' ? '取消管理员' : '设为管理员' }}</el-button>
        <el-button link :type="row.enabled ? 'danger' : 'success'" :disabled="accountBusy || row.id === userStore.id" @click="changeStatus(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="total > size" class="admin-pager" :layout="isMobile ? 'prev, pager, next' : 'prev, pager, next, total'" :pager-count="isMobile ? 5 : 7" :total="total" :page-size="size" :current-page="page + 1" @current-change="changePage" />
    <el-dialog v-model="passwordDialog" title="重置账号密码" :width="dialogWidth('440px')" @closed="password = ''">
      <p>为「{{ passwordTarget?.username }}」设置新密码</p>
      <el-input v-model="password" type="password" show-password placeholder="8–72 个字符" maxlength="72" autocomplete="new-password" @keyup.enter="savePassword" />
      <p class="cd-muted password-note">保存后该用户需要重新登录。请通过可信方式告知新密码。</p>
      <template #footer><el-button @click="passwordDialog = false">取消</el-button><el-button type="primary" :loading="savingPassword" @click="savePassword">保存新密码</el-button></template>
    </el-dialog>
    <el-dialog v-model="quotaDialog" title="账号扩容" :width="dialogWidth('480px')"
               :close-on-click-modal="!savingQuota" :close-on-press-escape="!savingQuota" :show-close="!savingQuota">
      <p>为「{{ quotaTarget?.username }}」扩容，当前总容量 {{ formatBytes(quotaTarget?.quotaBytes) }}</p>
      <el-form label-position="top">
        <el-form-item label="扩容后的总容量（GiB）">
          <el-input-number v-model="quotaGiB" :min="minimumQuotaGiB" :max="MAX_QUOTA_GIB" :step="1"
                           :precision="0" step-strictly :disabled="savingQuota" aria-label="扩容后的总容量" />
        </el-form-item>
      </el-form>
      <div class="quota-presets">
        <el-button v-for="value in quotaPresets" :key="value" size="small"
                   :type="quotaGiB === value ? 'primary' : 'default'"
                   :disabled="savingQuota || value * GIB <= quotaTarget?.quotaBytes"
                   @click="quotaGiB = value">{{ value }} GiB</el-button>
      </div>
      <p class="quota-note cd-muted">填写扩容后的总容量，不是追加容量。仅支持增加，最高100 GiB；按1024进制计算，与侧栏容量口径一致。</p>
      <p class="quota-note cd-muted">请先确认服务器磁盘空间充足。扩容不改变已用空间和单次上传大小限制，也不需要用户重新登录。</p>
      <template #footer>
        <el-button :disabled="savingQuota" @click="quotaDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingQuota" @click="saveQuota">确认扩容</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import * as adminApi from '../api/admin'
import { useUserStore } from '../stores/user'
import { useBreakpoint } from '../composables/useBreakpoint'
import { formatBytes, formatTime } from '../utils'

const userStore = useUserStore()
const router = useRouter()
const { isMobile, dialogWidth } = useBreakpoint()
const loading = ref(false)
const items = ref([])
const total = ref(0)
const page = ref(0)
const size = 20
const query = ref('')
const activeQuery = ref('')
const busy = ref(null)
const passwordDialog = ref(false)
const passwordTarget = ref(null)
const password = ref('')
const savingPassword = ref(false)
const GIB = 1024 ** 3
// Keep this ceiling in sync with AdminAccountService.MAX_QUOTA_BYTES.
const MAX_QUOTA_GIB = 100
const MAX_QUOTA_BYTES = MAX_QUOTA_GIB * GIB
const quotaPresets = [5, 10, 20, 50, 100]
const quotaDialog = ref(false)
const quotaTarget = ref(null)
const quotaGiB = ref(null)
const savingQuota = ref(false)
const accountBusy = computed(() => busy.value !== null || savingPassword.value || savingQuota.value)
const minimumQuotaGiB = computed(() => Math.min(MAX_QUOTA_GIB, Math.floor((quotaTarget.value?.quotaBytes || 0) / GIB) + 1))
const roleLabel = (role) => role === 'ADMIN' ? '管理员' : '普通用户'
let loadSequence = 0

async function load() {
  const sequence = ++loadSequence
  loading.value = true
  try {
    const data = await adminApi.listUsers(page.value, size, activeQuery.value)
    if (sequence !== loadSequence) return
    items.value = data.items
    total.value = data.total
  } catch (error) {
    // 错误提示由请求层处理。
  } finally { if (sequence === loadSequence) loading.value = false }
}
function search() { activeQuery.value = query.value.trim(); page.value = 0; load() }
function changePage(current) { page.value = current - 1; load() }
function replaceUser(updated) { items.value = items.value.map((item) => item.id === updated.id ? updated : item) }
async function endOwnSession() {
  userStore.logout()
  ElMessage.info('当前账号已更新，请重新登录')
  await router.replace({ name: 'login' })
}
function openPassword(row) { if (accountBusy.value) return; passwordTarget.value = row; password.value = ''; passwordDialog.value = true }
function openQuota(row) {
  if (accountBusy.value || row.quotaBytes >= MAX_QUOTA_BYTES) return
  quotaTarget.value = row
  quotaGiB.value = quotaPresets.find(value => value * GIB > row.quotaBytes) || minimumQuotaGiB.value
  quotaDialog.value = true
}
async function saveQuota() {
  if (accountBusy.value || !quotaTarget.value) return
  if (!Number.isInteger(quotaGiB.value) || quotaGiB.value <= 0 || quotaGiB.value > MAX_QUOTA_GIB) {
    ElMessage.warning('请选择1至100 GiB之间的整数容量'); return
  }
  const quotaBytes = quotaGiB.value * GIB
  if (quotaBytes <= quotaTarget.value.quotaBytes) { ElMessage.warning('新容量必须大于当前容量'); return }
  const targetId = quotaTarget.value.id
  savingQuota.value = true
  try {
    const updated = await adminApi.expandUserQuota(targetId, quotaBytes)
    replaceUser(updated)
    quotaDialog.value = false
    ElMessage.success(`已将「${updated.username}」总容量设为${formatBytes(updated.quotaBytes)}`)
    if (updated.id === userStore.id) await userStore.loadProfile()
  } catch (error) {
    // Another administrator may already have expanded this account further.
    if (error.response?.status === 409) {
      await load()
      const latest = items.value.find(item => item.id === targetId)
      if (latest) quotaTarget.value = latest
    }
  } finally { savingQuota.value = false }
}
async function savePassword() {
  if (accountBusy.value) return
  if (password.value.length < 8 || password.value.length > 72) { ElMessage.warning('密码需为 8–72 个字符'); return }
  savingPassword.value = true
  try {
    const updated = await adminApi.resetUserPassword(passwordTarget.value.id, password.value)
    passwordDialog.value = false
    password.value = ''
    replaceUser(updated)
    ElMessage.success('密码已重置')
    if (updated.id === userStore.id) await endOwnSession()
  } catch (error) {
    // 错误提示由请求层处理。
  } finally { savingPassword.value = false }
}
async function changeStatus(row) {
  if (accountBusy.value) return
  const enabled = !row.enabled
  if (!await ElMessageBox.confirm(enabled ? `恢复「${row.username}」的登录权限？` : `禁用「${row.username}」后，该用户将无法登录。`, enabled ? '启用账号' : '禁用账号', { type: 'warning', confirmButtonText: enabled ? '启用' : '禁用', cancelButtonText: '取消' }).catch(() => false)) return
  busy.value = row.id
  try {
    replaceUser(await adminApi.setUserEnabled(row.id, enabled))
    ElMessage.success(enabled ? '账号已启用' : '账号已禁用')
  } catch (error) {
    // 错误提示由请求层处理。
  } finally { busy.value = null }
}
async function changeRole(row) {
  if (accountBusy.value) return
  const role = row.role === 'ADMIN' ? 'USER' : 'ADMIN'
  const text = role === 'ADMIN' ? `将「${row.username}」设为管理员？管理员可以管理账号和复核文件。` : `将「${row.username}」设为普通用户？该账号将失去管理权限。`
  if (!await ElMessageBox.confirm(text, '更改身份', { type: 'warning', confirmButtonText: '确认更改', cancelButtonText: '取消' }).catch(() => false)) return
  busy.value = row.id
  try {
    replaceUser(await adminApi.setUserRole(row.id, role))
    ElMessage.success('账号身份已更新')
    if (row.id === userStore.id) await endOwnSession()
  } catch (error) {
    // 错误提示由请求层处理。
  } finally { busy.value = null }
}
onMounted(load)
</script>

<style scoped>
.user-search { width: 260px; }
.admin-note { margin-bottom: 16px; }
.admin-pager { margin-top: 16px; justify-content: flex-end; }
.user-email { font-size: 13px; overflow-wrap: anywhere; margin-top: 10px; color: #606266; }
.user-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
.user-actions .el-button { margin-left: 0; }
.password-note { line-height: 1.7; margin-bottom: 0; }
.quota-note { line-height: 1.7; margin-bottom: 0; }
.quota-presets { display: flex; flex-wrap: wrap; gap: 8px; }
.quota-presets .el-button { margin-left: 0; }
small { font-weight: normal; }
@media (max-width: 768px) { .user-search { width: 100%; } }
</style>
