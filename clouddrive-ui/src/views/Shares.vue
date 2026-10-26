<template>
  <div>
    <div class="cd-toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button :icon="Link" @click="$router.push({ name: 'open-share' })">打开别人的分享</el-button>
      <span v-if="!isMobile" class="cd-muted">共 {{ shares.length }} 个分享</span>
    </div>

    <!-- 手机：卡片列表。链接很长，表格里塞不下 -->
    <div v-if="isMobile" v-loading="loading" class="cd-card-list">
      <div v-if="!shares.length" class="cd-empty">还没有创建过分享</div>

      <div v-for="row in shares" :key="row.token" class="cd-card">
        <div class="cd-card-head">
          <span class="cd-card-name">
            <el-icon :color="row.fileDeleted ? '#f56c6c' : '#909399'"><Document /></el-icon>
            {{ row.displayName || '（文件已删除）' }}
          </span>
          <el-dropdown trigger="click" @command="(cmd) => onAction(cmd, row)">
            <el-button text :icon="MoreFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="copy">复制链接</el-dropdown-item>
                <el-dropdown-item command="reset">重置提取码</el-dropdown-item>
                <el-dropdown-item command="revoke" divided>取消分享</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <el-input :model-value="linkOf(row.token)" readonly size="small" class="share-link-input" />

        <div class="cd-card-meta">
          <span>{{ row.byteSize === null ? '-' : formatBytes(row.byteSize) }}</span>
          <el-tag v-if="row.fileDeleted" type="danger" size="small">文件已删除</el-tag>
          <el-tag v-else-if="expired(row.expiresAt)" type="danger" size="small">已过期</el-tag>
          <span v-else-if="!row.expiresAt">长期有效</span>
          <span v-else>有效至 {{ formatTime(row.expiresAt) }}</span>
        </div>
      </div>
    </div>

    <!-- 桌面：表格 -->
    <el-table v-else v-loading="loading" :data="shares" empty-text="还没有创建过分享" stripe>
      <el-table-column label="文件" min-width="200">
        <template #default="{ row }">
          <span class="share-file">
            <el-icon :color="row.fileDeleted ? '#f56c6c' : '#909399'"><Document /></el-icon>
            {{ row.displayName || '（文件已删除）' }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="大小" width="110">
        <template #default="{ row }">
          {{ row.byteSize === null ? '-' : formatBytes(row.byteSize) }}
        </template>
      </el-table-column>

      <el-table-column label="分享链接" min-width="280">
        <template #default="{ row }">
          <el-input :model-value="linkOf(row.token)" readonly size="small" />
        </template>
      </el-table-column>

      <el-table-column label="状态" width="180">
        <template #default="{ row }">
          <el-tag v-if="row.fileDeleted" type="danger" size="small">文件已删除</el-tag>
          <el-tag v-else-if="expired(row.expiresAt)" type="danger" size="small">已过期</el-tag>
          <span v-else-if="!row.expiresAt" class="cd-muted">长期有效</span>
          <el-tag v-else type="success" size="small">{{ formatTime(row.expiresAt) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="250" align="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="CopyDocument" @click="copy(row.token)">复制</el-button>
          <el-button link type="primary" :icon="Key" @click="openReset(row)">重置提取码</el-button>
          <el-button link type="danger" :icon="Delete" @click="revoke(row)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="share-note"
      title="这里无法查看原提取码。忘记后可通过「重置提取码」设置新的提取码，分享链接保持不变，旧提取码立即失效。"
    />

    <!-- 重置结果：这里是唯一还知道新提取码的时刻，直接给一键复制 -->
    <el-dialog v-model="resultDialog" title="提取码已重置" :width="dialogWidth('560px')">
      <el-tabs v-model="copyMode">
        <el-tab-pane label="免填链接（最省事）" name="auto">
          <el-input v-model="resultAutoLink" readonly type="textarea" :rows="2" />
          <p class="cd-muted dialog-hint">
            提取码已带在链接里，对方点开直接看到文件。
            请仅发送给需要访问文件的人。
          </p>
          <el-button type="primary" class="copy-btn" :icon="CopyDocument"
                     @click="copyValue(resultAutoLink, '免填链接已复制')">
            一键复制免填链接
          </el-button>
        </el-tab-pane>

        <el-tab-pane label="链接 + 提取码" name="split">
          <el-input v-model="resultText" readonly type="textarea" :rows="2" />
          <p class="cd-muted dialog-hint">
            整段发给对方即可，他在「打开分享」页一次粘贴就能自动识别。
          </p>
          <el-button type="primary" class="copy-btn" :icon="CopyDocument"
                     @click="copyValue(resultText, '链接和提取码已复制')">
            一键复制链接和提取码
          </el-button>
        </el-tab-pane>
      </el-tabs>

      <p class="cd-muted dialog-hint">
        分享链接保持不变，已经发出的链接仍然可用；旧提取码已经失效。
      </p>
    </el-dialog>

    <!-- 重置提取码 -->
    <el-dialog v-model="resetDialog" title="重置提取码" :width="dialogWidth('420px')">
      <el-form :label-width="isMobile ? '70px' : '90px'">
        <el-form-item label="文件">
          <span class="share-file-name">{{ resetTarget?.displayName || '（文件已删除）' }}</span>
        </el-form-item>
        <el-form-item label="新提取码">
          <el-input v-model="newPasscode" placeholder="4-32 位" maxlength="32" @keyup.enter="submitReset" />
        </el-form-item>
      </el-form>
      <p class="cd-muted dialog-hint">
        分享链接保持不变，旧提取码在保存后立即失效。
      </p>
      <template #footer>
        <el-button @click="resetDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReset">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, Document, Key, Link, MoreFilled, Refresh } from '@element-plus/icons-vue'
import * as shareApi from '../api/share'
import { useBreakpoint } from '../composables/useBreakpoint'
import { copyText, formatBytes, formatTime } from '../utils'

const { isMobile, dialogWidth } = useBreakpoint()

const loading = ref(false)
const submitting = ref(false)
const shares = ref([])

const resetDialog = ref(false)
const resetTarget = ref(null)
const newPasscode = ref('')

const resultDialog = ref(false)
const resultAutoLink = ref('')
const resultText = ref('')
const copyMode = ref('auto')

const linkOf = (token) => shareApi.shareLinkOf(token)

const expired = (text) => Boolean(text) && new Date(text) <= new Date()

function onAction(command, row) {
  if (command === 'copy') copy(row.token)
  else if (command === 'reset') openReset(row)
  else if (command === 'revoke') revoke(row)
}

async function load() {
  loading.value = true
  try {
    shares.value = await shareApi.listShares()
  } catch (error) {
    // 已提示
  } finally {
    loading.value = false
  }
}

async function copy(token) {
  if (await copyText(linkOf(token))) {
    // 这里只能复制链接：提取码只存摘要，列表页拿不到原文。
    // 想要连提取码一起发，用"重置提取码"——重置后会给出可一键复制的完整内容。
    ElMessage.success('链接已复制（提取码需另行告知，或用「重置提取码」重新生成一份完整内容）')
  } else {
    ElMessage.warning('自动复制失败，请手动选中链接复制')
  }
}

async function copyValue(value, okMessage) {
  if (await copyText(value)) {
    ElMessage.success(okMessage)
  } else {
    ElMessage.warning('自动复制失败，请手动选中内容复制')
  }
}

function openReset(row) {
  resetTarget.value = row
  // 给个随机初值，省得用户自己想；想改也可以直接改
  newPasscode.value = String(Math.floor(1000 + Math.random() * 9000))
  resetDialog.value = true
}

async function submitReset() {
  if (newPasscode.value.trim().length < 4) {
    ElMessage.warning('提取码至少 4 位')
    return
  }
  submitting.value = true
  try {
    await shareApi.resetPasscode(resetTarget.value.token, newPasscode.value.trim())
    const code = newPasscode.value.trim()
    const token = resetTarget.value.token
    resetDialog.value = false
    // 这是唯一还知道新提取码的时刻，直接给出可一键复制的完整内容，
    // 否则又会回到"忘了没处查"的老问题
    resultAutoLink.value = shareApi.autoFillLinkOf(token, code)
    resultText.value = shareApi.shareTextOf(token, code)
    copyMode.value = 'auto'
    resultDialog.value = true
  } catch (error) {
    // 已提示
  } finally {
    submitting.value = false
  }
}

async function revoke(row) {
  const confirmed = await ElMessageBox.confirm(
    '取消后该链接立即失效，已经拿到链接的人也无法再下载。确定吗？',
    '取消分享',
    { type: 'warning', confirmButtonText: '取消分享', cancelButtonText: '再想想' }
  ).catch(() => false)
  if (!confirmed) return

  try {
    await shareApi.revokeShare(row.token)
    ElMessage.success('分享已取消')
    await load()
  } catch (error) {
    // 已提示
  }
}

onMounted(load)
</script>

<style scoped>
.share-note {
  margin-top: 16px;
  line-height: 1.6;
}

.share-link-input {
  margin-top: 10px;
}

.share-file {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  overflow-wrap: anywhere;
}

.share-file-name {
  overflow-wrap: anywhere;
}

.dialog-hint {
  margin: 12px 0 0;
  line-height: 1.6;
}

.copy-btn {
  margin-top: 12px;
  width: 100%;
}
</style>
