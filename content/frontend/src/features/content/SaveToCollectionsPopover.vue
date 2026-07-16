<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { Bookmark, CheckSquare, Globe2, Lock, Square, X } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import type { SavedCollection } from "@/api/types";

const props = defineProps<{ postId: string }>();
const emit = defineEmits<{ close: []; saved: [collectionIds: string[]] }>();

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
    errorMessage.value = error instanceof Error ? error.message : "Не удалось загрузить коллекции";
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
    errorMessage.value = error instanceof Error ? error.message : "Не удалось сохранить коллекции";
  } finally {
    isSaving.value = false;
  }
}
</script>

<template>
  <div class="collection-popover-backdrop" @click.self="emit('close')">
    <section class="collection-popover" role="dialog" aria-modal="true" aria-label="Сохранить в коллекции">
      <header>
        <strong>Сохранить в коллекции</strong>
        <button type="button" aria-label="Закрыть" @click="emit('close')"><X :size="17" /></button>
      </header>

      <div v-if="isLoading" class="collection-popover__state"><i class="pi pi-spinner pi-spin" /><span>Загружаем</span></div>
      <template v-else>
        <div class="collection-list">
          <button v-for="collection in collections" :key="collection.id" type="button" class="collection-row" :class="{ 'is-selected': selected.has(collection.id) }" @click="toggle(collection.id)">
            <CheckSquare v-if="selected.has(collection.id)" :size="18" />
            <Square v-else :size="18" />
            <span><strong>{{ collection.title }}</strong><small>{{ collection.itemCount }} постов</small></span>
            <Globe2 v-if="collection.visibility === 'PUBLIC'" :size="17" />
            <Lock v-else :size="17" />
          </button>
          <p v-if="!collections.length" class="collection-empty">Коллекций пока нет.</p>
        </div>
      </template>

      <p v-if="errorMessage" class="collection-error">{{ errorMessage }}</p>
      <footer><button type="button" class="collection-save" :disabled="isSaving || isLoading" @click="save"><Bookmark :size="17" /><span>{{ isSaving ? "Сохраняем" : "Готово" }}</span></button></footer>
    </section>
  </div>
</template>

<style scoped>
.collection-popover-backdrop {
  position: fixed;
  z-index: 150;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 18px;
  background: rgba(40, 85, 255, .16);
  backdrop-filter: blur(10px);
}

.collection-popover {
  width: min(400px, calc(100vw - 28px));
  max-height: min(640px, calc(100dvh - 28px));
  display: grid;
  gap: 14px;
  overflow: hidden;
  padding: 18px;
  background: #fff;
  color: #17264b;
  box-shadow: 0 22px 45px rgba(40, 85, 255, .25);
  font-family: "Nunito", "Avenir Next", "Roboto", sans-serif;
}

.collection-popover header,
.collection-popover footer { display: flex; align-items: center; }
.collection-popover header { justify-content: space-between; gap: 14px; }
.collection-popover header strong { font-size: 18px; line-height: 1.15; }
.collection-popover button { font: inherit; }
.collection-popover header button { width: 34px; height: 34px; display: grid; place-items: center; border: 0; border-radius: 50%; background: #ff4fa3; color: #fff; cursor: pointer; }
.collection-list { display: grid; gap: 8px; overflow-y: auto; padding-right: 2px; }
.collection-row { min-width: 0; min-height: 56px; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; border: 0; padding: 10px 12px; background: #e9efff; color: inherit; text-align: left; cursor: pointer; }
.collection-row.is-selected { background: #b8f348; box-shadow: 0 9px 18px rgba(184, 243, 72, .24); }
.collection-row span { min-width: 0; display: grid; gap: 2px; }
.collection-row strong,
.collection-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.collection-row small,
.collection-empty,
.collection-error { margin: 0; color: #64749a; font-size: 12px; font-weight: 800; }
.collection-error { color: #d12670; }
.collection-save { width: 100%; min-height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 999px; background: #2855ff; color: #fff; box-shadow: 0 8px 18px rgba(40, 85, 255, .25); font-weight: 900; cursor: pointer; }
.collection-save:disabled { opacity: .55; cursor: default; }
.collection-popover__state { min-height: 110px; display: grid; place-items: center; gap: 7px; color: #64749a; font-weight: 900; }
.collection-popover button:focus-visible { outline: 3px solid #2855ff; outline-offset: 3px; }
</style>
