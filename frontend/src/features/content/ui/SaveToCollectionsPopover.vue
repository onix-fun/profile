<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ContentService } from "@/shared/api/contentService";
import type { CollectionItemRef, SavedCollection } from "@/shared/api/types";
import { activateFocusTrap } from "@/shared/lib/focusTrap";
import OnixIcon from "@/shared/ui/OnixIcon.vue";

const props = defineProps<{
  postId?: string;
  itemRef?: CollectionItemRef;
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
const dialog = ref<HTMLElement | null>(null);
let deactivateFocusTrap: (() => void) | null = null;

const refForItem = computed<CollectionItemRef>(() => props.itemRef || {
  serviceKey: "content",
  itemType: "post",
  itemId: props.postId || "",
});

onMounted(async () => {
  void loadState();
  await nextTick();
  if (dialog.value) deactivateFocusTrap = activateFocusTrap(dialog.value, () => emit("close"));
});
onBeforeUnmount(() => deactivateFocusTrap?.());
watch(refForItem, loadState);

async function loadState() {
  isLoading.value = true;
  errorMessage.value = "";
  try {
    const actor = await ContentService.currentActor();
    const [items, state] = await Promise.all([
      ContentService.collections(actor.activeOwner.id, actor.activeOwner.ownerType || "USER"),
      ContentService.itemCollections(refForItem.value),
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
    const state = await ContentService.setItemCollections(refForItem.value, [...selected.value]);
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
    <section ref="dialog" class="collection-popover" role="dialog" aria-modal="true" aria-label="Save to collections" tabindex="-1">
      <header>
        <strong>Save</strong>
        <button type="button" aria-label="Close" @click="emit('close')">
          <OnixIcon name="close" :size="18" />
        </button>
      </header>

      <div v-if="isLoading" class="collection-popover__state">
        <OnixIcon name="refresh" class="onix-icon--spin" :size="20" />
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
            <OnixIcon :name="selected.has(collection.id) ? 'check-square' : 'square'" :size="18" />
            <span>
              <strong>{{ collection.title }}</strong>
              <small>{{ collection.itemCount }} posts</small>
            </span>
            <OnixIcon :name="collection.visibility === 'PUBLIC' ? 'globe' : 'lock'" :size="18" />
          </button>
          <p v-if="!collections.length" class="collection-empty">No collections yet</p>
        </div>

      </template>

      <p v-if="errorMessage" class="collection-error">{{ errorMessage }}</p>
      <footer>
        <button type="button" class="collection-save" :disabled="isSaving || isLoading" @click="save">
          <OnixIcon name="bookmark" :size="18" />
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
  background: var(--onix-color-overlay);
  backdrop-filter: blur(6px);
}

.collection-popover {
  width: min(390px, calc(100vw - 28px));
  max-height: min(640px, calc(100dvh - 28px));
  display: grid;
  gap: 12px;
  overflow: hidden;
  
  border-radius: 12px;
  padding: 14px;
  background: var(--onix-color-surface-floating, var(--onix-color-surface-base));
  color: var(--onix-color-text);
  
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
  width: var(--onix-control-md);
  height: var(--onix-control-md);
  
  border-radius: var(--onix-radius-pill);
  display: grid;
  place-items: center;
  background: var(--onix-color-surface-muted);
  color: var(--onix-color-text-muted);
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
  
  background: var(--onix-color-tone-success-soft);
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
  color: var(--onix-color-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.collection-save:disabled {
  cursor: default;
  opacity: 0.55;
}

.collection-save {
  width: 100%;
  min-height: var(--onix-control-md);
  
  border-radius: var(--onix-radius-pill);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--onix-tone-solid, var(--onix-color-text));
  color: var(--onix-tone-on-solid, var(--onix-color-surface-base));
  font-weight: 900;
  cursor: pointer;
}

.collection-popover__state {
  min-height: 110px;
  display: grid;
  place-items: center;
  gap: 6px;
  color: var(--onix-color-text-muted);
  font-weight: 900;
}
</style>
