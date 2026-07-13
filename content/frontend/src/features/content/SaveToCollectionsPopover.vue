<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { ContentService } from "@/api/contentService";
import type { SavedCollection } from "@/api/types";

const props = defineProps<{
  postId: string;
}>();

const emit = defineEmits<{
  close: [];
  saved: [collectionIds: string[]];
}>();

const collections = ref<SavedCollection[]>([]);
const selected = ref<Set<string>>(new Set());
const isLoading = ref(true);
const isSaving = ref(false);
const errorMessage = ref("");

onMounted(loadState);
watch(() => props.postId, loadState);

async function loadState() {
  isLoading.value = true;
  errorMessage.value = "";
  try {
    const actor = await ContentService.currentActor();
    const [items, state] = await Promise.all([
      ContentService.collections(actor.activeOwner.id, actor.activeOwner.ownerType || "USER"),
      ContentService.postCollections(props.postId),
    ]);
    collections.value = items;
    selected.value = new Set(state.collectionIds);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Unable to load collections";
  } finally {
    isLoading.value = false;
  }
}

function toggle(id: string) {
  const next = new Set(selected.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selected.value = next;
}

async function save() {
  isSaving.value = true;
  errorMessage.value = "";
  try {
    const state = await ContentService.setPostCollections(props.postId, [...selected.value]);
    emit("saved", state.collectionIds);
    emit("close");
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Unable to save collections";
  } finally {
    isSaving.value = false;
  }
}
</script>

<template>
  <div class="collection-popover-backdrop" @click.self="emit('close')">
    <section class="collection-popover" role="dialog" aria-label="Save to collections">
      <header>
        <strong>Save</strong>
        <button type="button" aria-label="Close" @click="emit('close')">
          <i class="pi pi-times"></i>
        </button>
      </header>

      <div v-if="isLoading" class="collection-popover__state">
        <i class="pi pi-spinner pi-spin"></i>
        <span>Loading</span>
      </div>

      <template v-else>
        <div class="collection-list">
          <button
            v-for="collection in collections"
            :key="collection.id"
            type="button"
            class="collection-row"
            :class="{ 'is-selected': selected.has(collection.id) }"
            @click="toggle(collection.id)"
          >
            <i :class="selected.has(collection.id) ? 'pi pi-check-square' : 'pi pi-stop'"></i>
            <span>
              <strong>{{ collection.title }}</strong>
              <small>{{ collection.itemCount }} posts</small>
            </span>
            <i :class="collection.visibility === 'PUBLIC' ? 'pi pi-globe' : 'pi pi-lock'"></i>
          </button>
          <p v-if="!collections.length" class="collection-empty">No collections yet</p>
        </div>

      </template>

      <p v-if="errorMessage" class="collection-error">{{ errorMessage }}</p>
      <footer>
        <button type="button" class="collection-save" :disabled="isSaving || isLoading" @click="save">
          <i class="pi pi-bookmark"></i>
          <span>{{ isSaving ? "Saving" : "Done" }}</span>
        </button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.collection-popover-backdrop {
  position: fixed;
  z-index: 40;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgba(15, 23, 42, 0.18);
  backdrop-filter: blur(6px);
}

.collection-popover {
  width: min(390px, calc(100vw - 28px));
  max-height: min(640px, calc(100dvh - 28px));
  display: grid;
  gap: 12px;
  overflow: hidden;
  border: 1px solid var(--surface-active, rgba(15, 23, 42, 0.1));
  border-radius: 12px;
  padding: 14px;
  background: var(--surface-raised, #ffffff);
  color: var(--text, #111827);
  box-shadow: 0 28px 90px rgba(15, 23, 42, 0.24);
}

.collection-popover header,
.collection-popover footer {
  display: flex;
  align-items: center;
}

.collection-popover header {
  justify-content: space-between;
}

.collection-popover header strong {
  font-size: 17px;
  font-weight: 900;
}

.collection-popover button {
  font: inherit;
}

.collection-popover header button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--surface-muted, #eef2f7);
  color: var(--muted, #64748b);
  cursor: pointer;
}

.collection-list {
  display: grid;
  gap: 7px;
  overflow-y: auto;
  padding-right: 2px;
}

.collection-row {
  min-width: 0;
  min-height: 52px;
  border: 1px solid var(--surface-active, #e2e8f0);
  border-radius: 10px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.collection-row.is-selected {
  border-color: #0f766e;
  background: rgba(20, 184, 166, 0.1);
}

.collection-row span {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.collection-row strong,
.collection-row small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collection-row small,
.collection-empty,
.collection-error {
  color: var(--muted, #64748b);
  font-size: 12px;
  font-weight: 800;
}

.collection-save:disabled {
  cursor: default;
  opacity: 0.55;
}

.collection-save {
  width: 100%;
  min-height: 42px;
  border: 0;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--btn-primary-bg, #111827);
  color: var(--btn-primary-text, #ffffff);
  font-weight: 900;
  cursor: pointer;
}

.collection-popover__state {
  min-height: 110px;
  display: grid;
  place-items: center;
  gap: 6px;
  color: var(--muted, #64748b);
  font-weight: 900;
}
</style>
