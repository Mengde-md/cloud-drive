<template>
  <div>
    <div class="cd-toolbar">
      <el-button :icon="Refresh" @click="load">刷新</el-button>
      <el-button type="danger" :icon="Delete" :disabled="total === 0" @click="purge">
        清空回收站
      </el-button>
      <span v-if="!isMobile" class="cd-muted">共 {{ total }} 项</span>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="trash-note"
      :title="noteText"
    />

    <!-- 手机：卡片列表 -->
    <div v-if="isMobile" v-loading="loading" class="cd-card-list">
      <div v-if="!items.length" class="cd-empty">回收站是空的</div>

      <div v-for="row in items" :key="row.id" class="cd-card">
        <div class="cd-card-head">
          <span class="cd-card-name">
            <el-icon :color="row.kind === 'DIRECTORY' ? '#e6a23c' : '#909399'">
              <Folder v-if="row.kind === 'DIRECTORY'" />
              <Document v-else />
            </el-icon>
            {{ row.displayName }}
          </span>
          <el-button link type="primary" :icon="RefreshLeft" @click="restore(row)">恢复</el-button>
        </div>
        <div class="cd-card-meta">
          <span>{{ row.kind === 'DIRECTORY' ? '文件夹' : formatBytes(row.byteSize) }}</span>
          <span>{{ formatTime(row.deletedAt) }}</span>
        </div>
      </div>
    </div>

    <!-- 桌面：表格 -->
    <el-table v-else v-loading="loading" :data="items" empty-text="回收站是空的" stripe>
      <el-table-column label="名称" min-width="240">
        <template #default="{ row }">
          <span class="trash-name">
            <el-icon :color="row.kind === 'DIRECTORY' ? '#e6a23c' : '#909399'">
              <Folder v-if="row.kind === 'DIRECTORY'" />
              <Document v-else />
            </el-icon>
            {{ row.displayName }}
          </span>
        </template>
      </el-table-column>

      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.kind === 'DIRECTORY' ? 'warning' : 'info'">
            {{ row.kind === 'DIRECTORY' ? '文件夹' : '文件' }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          {{ row.kind === 'DIRECTORY' ? '-' : formatBytes(row.byteSize) }}
        </template>
      </el-table-column>

      <el-table-column label="删除时间" width="200">
        <template #default="{ row }">{{ formatTime(row.deletedAt) }}</template>
      </el-table-column>

      <el-table-column label="操作" width="140" align="right">
        <template #default="{ row }">
          <el-button link type="primary" :icon="RefreshLeft" @click="restore(row)">恢复</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > size"
      class="trash-pager"
      :layout="isMobile ? 'prev, pager, next' : 'prev, pager, next, total'"
      :pager-count="isMobile ? 5 : 7"
      :total="total"
      :page-size="size"
      :current-page="page + 1"
      @current-change="onPageChange"
    />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, Folder, Refresh, RefreshLeft } from '@element-plus/icons-vue'
import * as fileApi from '../api/file'
import { useBreakpoint } from '../composables/useBreakpoint'
import { formatBytes, formatTime } from '../utils'

const emit = defineEmits(['refresh-quota'])
const { isMobile } = useBreakpoint()

const loading = ref(false)
const items = ref([])
const total = ref(0)
const page = ref(0)
const size = ref(20)

// 列表里只会出现"当初点删除的那一个"，子条目不铺开——这一点要跟用户说清楚，
// 否则删了一个有几十个文件的目录，看到回收站只有一行会以为丢了东西。
const noteText = '回收站中的文件仍占用存储空间，清空后释放空间。'
  + '删除文件夹时里面的内容会一起进回收站，列表只显示被删的文件夹本身，恢复时整体恢复。'

async function load() {
  loading.value = true
  try {
    const data = await fileApi.listTrash(page.value, size.value)
    items.value = data.items
    total.value = data.total
  } catch (error) {
    // 已提示
  } finally {
    loading.value = false
  }
}

function onPageChange(current) {
  page.value = current - 1
  load()
}

async function restore(row) {
  try {
    const data = await fileApi.restoreEntry(row.id)
    const count = data?.affectedCount ?? 1
    ElMessage.success(count > 1 ? `已恢复 ${count} 项到原位置` : '已恢复到原位置')
    await load()
  } catch (error) {
    // 若原位置已有同名条目，后端返回 409，拦截器会提示
  }
}

async function purge() {
  const confirmed = await ElMessageBox.confirm(
    '清空后文件将被永久删除，无法恢复。确定继续吗？',
    '清空回收站',
    { type: 'warning', confirmButtonText: '永久删除', cancelButtonText: '取消' }
  ).catch(() => false)
  if (!confirmed) return

  try {
    const data = await fileApi.purgeTrash()
    ElMessage.success(`已永久删除 ${data.purgedCount} 项`)
    page.value = 0
    await load()
    emit('refresh-quota')
  } catch (error) {
    // 已提示
  }
}

onMounted(load)
</script>

<style scoped>
.trash-note {
  margin-bottom: 16px;
  line-height: 1.6;
}

.trash-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.trash-pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
