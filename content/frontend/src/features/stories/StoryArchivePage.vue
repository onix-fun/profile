<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ChevronLeft, ChevronRight, History, Lock, X } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import { profileUrl } from "@/api/navigation";
import type { Story, StoryArchivePeriod, StoryArchiveResponse, StoryBlock } from "@/api/types";
import { displayUsername } from "@/features/display/displayText";

const route = useRoute();
const router = useRouter();

const archive = ref<StoryArchiveResponse | null>(null);
const isLoading = ref(true);
const errorMessage = ref("");
const periods = ref<StoryArchivePeriod[]>([]);
const selectedPeriod = ref("");
const orbitAngle = ref(0);
const dragStart = ref<number | null>(null);
let suppressClickUntil = 0;

const ownerType = computed<"USER" | "ORGANIZATION">(() => route.query.ownerType === "ORGANIZATION" ? "ORGANIZATION" : "USER");
const ownerId = computed(() => typeof route.query.ownerId === "string" ? route.query.ownerId : "");
const cursor = computed(() => typeof route.query.cursor === "string" ? route.query.cursor : null);
const stories = computed(() => archive.value?.stories || []);
const periodStories = computed(() => selectedPeriod.value
  ? stories.value.filter((story) => (story.createdAt || story.expiresAt || "").slice(0, 7) === selectedPeriod.value)
  : stories.value);
const periodLabel = computed(() => selectedPeriod.value
  ? new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric" }).format(new Date(`${selectedPeriod.value}-01T12:00:00`))
  : "Все истории");
const ownerName = computed(() => displayUsername(archive.value?.owner?.username, ownerType.value === "ORGANIZATION" ? "Organization" : "User"));
const ownerAvatar = computed(() => archive.value?.owner?.avatarUrl || "");
const ownerProfilePath = computed(() => {
  const username = archive.value?.owner?.username;
  if (!username) return "";
  return profileUrl(`/${ownerType.value === "ORGANIZATION" ? "o" : "u"}/${encodeURIComponent(username)}`, true);
});

onMounted(loadArchive);

async function loadArchive() {
  isLoading.value = true;
  errorMessage.value = "";
  try {
    if (!ownerId.value) throw new Error("ownerId is required");
    const archivePeriods = typeof ContentService.storyArchivePeriods === "function"
      ? ContentService.storyArchivePeriods(ownerId.value, ownerType.value)
      : Promise.resolve({ periods: [] as StoryArchivePeriod[] });
    const [firstArchive, periodResponse] = await Promise.all([
      ContentService.storyArchive(ownerId.value, cursor.value, 80, ownerType.value),
      archivePeriods,
    ]);
    const allStories = [...firstArchive.stories];
    let nextCursor = firstArchive.nextCursor || null;
    const visitedCursors = new Set<string>();
    while (nextCursor && !visitedCursors.has(nextCursor) && allStories.length < 1_000) {
      visitedCursors.add(nextCursor);
      const page = await ContentService.storyArchive(ownerId.value, nextCursor, 80, ownerType.value);
      allStories.push(...page.stories.filter((story) => !allStories.some((item) => item.id === story.id)));
      nextCursor = page.nextCursor || null;
    }
    archive.value = { ...firstArchive, stories: allStories, nextCursor };
    periods.value = periodResponse.periods;
    const requestedPeriod = typeof route.query.period === "string" ? route.query.period : "";
    selectedPeriod.value = periods.value.some((item) => item.period === requestedPeriod)
      ? requestedPeriod
      : periods.value[0]?.period || "";
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Archive unavailable";
  } finally {
    isLoading.value = false;
  }
}

function selectPeriod(period: string) {
  selectedPeriod.value = period;
  orbitAngle.value = 0;
}

function selectPeriodEvent(event: Event) {
  selectPeriod((event.target as HTMLSelectElement).value);
}

function movePeriod(delta: number) {
  const index = periods.value.findIndex((period) => period.period === selectedPeriod.value);
  const next = periods.value[index + delta];
  if (next) selectPeriod(next.period);
}

function orbitStyle(index: number) {
  const total = Math.max(periodStories.value.length, 1);
  const angle = orbitAngle.value + (360 / total) * index - 90;
  const radius = Math.min(230, 90 + total * 10);
  return { transform: `translate(-50%, -50%) rotate(${angle}deg) translateX(${radius}px) rotate(${-angle}deg)` };
}

function beginOrbit(event: PointerEvent) {
  dragStart.value = event.clientX;
  (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
}
function moveOrbit(event: PointerEvent) {
  if (dragStart.value === null) return;
  const delta = event.clientX - dragStart.value;
  if (Math.abs(delta) > 2) suppressClickUntil = Date.now() + 250;
  orbitAngle.value += delta * 0.8;
  dragStart.value = event.clientX;
}
function endOrbit() { dragStart.value = null; }

function rotateOrbit(event: KeyboardEvent) {
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault();
  orbitAngle.value += event.key === "ArrowLeft" ? -18 : 18;
}

function previewBlock(story: Story): StoryBlock | undefined {
  return story.blocks.find((block) => block.type === "IMAGE" || block.type === "VIDEO") || story.blocks[0];
}

function previewSource(story: Story): string {
  const block = previewBlock(story);
  return block ? ContentService.mediaSource(block) : "";
}

function previewPoster(story: Story): string {
  const block = previewBlock(story);
  return block && typeof block.data.posterUrl === "string" ? block.data.posterUrl : "";
}

function previewType(story: Story): StoryBlock["type"] | "" {
  return previewBlock(story)?.type || "";
}

function storyCaption(story: Story): string {
  return story.blocks
    .map((block) => typeof block.data.text === "string" ? block.data.text : typeof block.data.caption === "string" ? block.data.caption : "")
    .filter(Boolean)
    .join(" ")
    .trim();
}

function archiveDate(story: Story): string {
  const value = story.createdAt || story.expiresAt;
  if (!value) return "";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function openStory(story: Story) {
  if (Date.now() < suppressClickUntil) return;
  void router.push({
    path: `/story/${encodeURIComponent(story.id)}`,
    query: {
      archive: "1",
      author: ownerId.value,
      ownerType: ownerType.value,
      from: route.fullPath,
    },
  });
}

function goBack() {
  const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "";
  if (redirect) {
    window.location.assign(redirect);
    return;
  }
  void router.push("/");
}
</script>

<template>
  <section class="archive-view">
    <div class="archive-ambient" aria-hidden="true"></div>

    <div class="archive-controls">
      <button type="button" class="archive-control" aria-label="Close archive" @click="goBack">
        <X :size="22" />
      </button>
    </div>

    <section v-if="isLoading" class="archive-state">Открываем архив</section>
    <section v-else-if="errorMessage" class="archive-state archive-state--panel">
      <Lock :size="34" />
      <strong>Archive unavailable</strong>
      <span>{{ errorMessage }}</span>
    </section>

    <main v-else class="archive-scene" aria-label="Story archive">
      <a v-if="ownerProfilePath" class="archive-owner" :href="ownerProfilePath">
        <span class="archive-owner__avatar">
          <img v-if="ownerAvatar" :src="ownerAvatar" alt="" />
          <i v-else-if="ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
          <i v-else class="pi pi-user"></i>
        </span>
        <span>
          <strong>{{ ownerName }}</strong>
          <small>Archive</small>
        </span>
      </a>

      <header class="archive-title">
        <History :size="30" />
        <div>
          <h1>Story archive</h1>
          <p>{{ periodStories.length ? `${periodStories.length} историй · ${periodLabel}` : "Нет историй за этот период" }}</p>
        </div>
      </header>

      <div v-if="periods.length" class="archive-periods" aria-label="Период архива">
        <button type="button" aria-label="Предыдущий месяц" :disabled="selectedPeriod === periods[periods.length - 1]?.period" @click="movePeriod(1)"><ChevronLeft :size="18" /></button>
        <select :value="selectedPeriod" aria-label="Месяц историй" @change="selectPeriodEvent">
          <option v-for="period in periods" :key="period.period" :value="period.period">{{ new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric' }).format(new Date(`${period.period}-01T12:00:00`)) }} · {{ period.count }}</option>
        </select>
        <button type="button" aria-label="Следующий месяц" :disabled="selectedPeriod === periods[0]?.period" @click="movePeriod(-1)"><ChevronRight :size="18" /></button>
      </div>

      <div v-if="periodStories.length" class="archive-timeline archive-orbit" tabindex="0" aria-label="Орбита архивных историй" @keydown="rotateOrbit" @pointerdown="beginOrbit" @pointermove="moveOrbit" @pointerup="endOrbit" @pointercancel="endOrbit">
        <div class="archive-orbit__core"><strong>{{ periodLabel }}</strong><span>Потяните орбиту</span></div>
        <button
          v-for="(story, index) in periodStories"
          :key="story.id"
          type="button"
          class="archive-card"
          :style="orbitStyle(index)"
          @click="openStory(story)"
        >
          <span class="archive-card__media" :class="{ 'is-empty': !previewSource(story) }">
            <img v-if="previewType(story) === 'VIDEO' && previewPoster(story)" :src="previewPoster(story)" alt="" />
            <video
              v-else-if="previewType(story) === 'VIDEO' && previewSource(story)"
              :src="previewSource(story)"
              muted
              playsinline
              preload="none"
            />
            <img v-else-if="previewSource(story)" :src="previewSource(story)" alt="" />
            <History v-else :size="34" />
          </span>
          <span class="archive-card__copy">
            <strong>{{ storyCaption(story) || "Story" }}</strong>
            <small>{{ archiveDate(story) }}</small>
          </span>
        </button>
      </div>

      <section v-else class="archive-empty">
        <History :size="36" />
        <strong>Archive is empty</strong>
        <span>Stories will appear here after they expire.</span>
      </section>
    </main>
  </section>
</template>

<style scoped>
.archive-view {
  position: fixed;
  z-index: 180;
  inset: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 18% 20%, rgba(0, 229, 255, 0.24), transparent 30%),
    radial-gradient(circle at 84% 12%, rgba(255, 79, 123, 0.22), transparent 28%),
    linear-gradient(138deg, rgba(246, 255, 24, 0.95) 0 13%, transparent 13%),
    #05070b;
  color: #ffffff;
}

.archive-ambient {
  position: fixed;
  inset: 8%;
  border-radius: 999px;
  background:
    conic-gradient(from 160deg, rgba(0, 229, 255, 0.18), transparent 22%, rgba(181, 76, 255, 0.18), transparent 58%, rgba(20, 247, 104, 0.14)),
    radial-gradient(circle, rgba(255, 255, 255, 0.06), transparent 62%);
  filter: blur(22px);
  opacity: 0.8;
  pointer-events: none;
}

.archive-controls {
  position: fixed;
  z-index: 205;
  top: max(18px, env(safe-area-inset-top));
  right: 20px;
}

.archive-control {
  width: 42px;
  height: 42px;
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-coral);
  color: #ffffff;
  cursor: pointer;
  backdrop-filter: blur(16px);
  box-shadow: var(--comic-shadow-small);
  transform: rotate(3deg);
}

.archive-scene {
  position: relative;
  z-index: 185;
  min-height: 100dvh;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 24px;
  padding: max(76px, env(safe-area-inset-top)) 0 max(38px, env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.archive-owner,
.archive-title {
  margin-inline: clamp(18px, 6vw, 74px);
}

.archive-owner {
  width: max-content;
  max-width: min(360px, calc(100vw - 120px));
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  text-decoration: none;
  box-shadow:
    var(--comic-shadow-small);
}

.archive-owner__avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  overflow: hidden;
  border: 3px solid var(--comic-ink);
  border-radius: 8px;
  background: var(--comic-cyan);
}

.archive-owner__avatar img {
  width: 100%;
  height: 100%;
  border: 2px solid var(--comic-ink);
  border-radius: inherit;
  object-fit: cover;
}

.archive-owner span:last-child {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.archive-owner strong,
.archive-owner small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-owner strong {
  font-size: 13px;
  font-weight: 900;
}

.archive-owner small {
  color: #46505a;
  font-size: 11px;
  font-weight: 800;
}

.archive-title {
  display: flex;
  align-items: center;
  gap: 14px;
}

.archive-title > svg {
  width: 56px;
  height: 56px;
  border: var(--comic-line);
  border-radius: 8px;
  padding: 10px;
  display: grid;
  place-items: center;
  background: var(--comic-lime);
  color: var(--comic-ink);
  font-size: 22px;
  box-shadow: var(--comic-shadow-small);
  transform: rotate(-4deg);
}

.archive-title h1,
.archive-title p {
  margin: 0;
}

.archive-title h1 {
  font-family: var(--display-font);
  font-weight: 400;
  font-size: clamp(30px, 5vw, 58px);
  line-height: 0.95;
}

.archive-title p {
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.68);
  font-weight: 850;
}

.archive-timeline {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(210px, 24vw);
  align-items: center;
  gap: 22px;
  min-height: 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 10px clamp(18px, 6vw, 74px) 30px;
  scroll-snap-type: x mandatory;
  scrollbar-color: rgba(45, 212, 191, 0.38) transparent;
  scrollbar-width: thin;
}

.archive-card {
  scroll-snap-align: center;
  min-width: 0;
  border: 0;
  border: var(--comic-line);
  border-radius: 8px;
  padding: 10px;
  display: grid;
  grid-template-rows: minmax(260px, 54dvh) auto;
  gap: 12px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  text-align: left;
  cursor: pointer;
  box-shadow:
    var(--comic-shadow);
  transition: transform 180ms ease, background 180ms ease;
}

.archive-card:hover,
.archive-card:focus-visible {
  transform: translateY(-4px) scale(1.015);
  background: var(--comic-yellow);
}

.archive-card__media {
  min-width: 0;
  min-height: 0;
  border: 3px solid var(--comic-ink);
  border-radius: 8px;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: var(--comic-ink);
  color: rgba(255, 255, 255, 0.68);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.1);
}

.archive-card__media img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.archive-card__media.is-empty i {
  font-size: 34px;
}

.archive-card__copy {
  min-width: 0;
  display: grid;
  gap: 5px;
  padding: 0 6px 4px;
}

.archive-card__copy strong,
.archive-card__copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.archive-card__copy strong {
  font-size: 14px;
  font-weight: 900;
}

.archive-card__copy small {
  color: #46505a;
  font-size: 12px;
  font-weight: 850;
}

.archive-state,
.archive-empty {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.7);
  font-weight: 900;
}

.archive-state--panel,
.archive-empty {
  align-content: center;
  gap: 9px;
  text-align: center;
}

.archive-empty {
  min-height: 46dvh;
  margin: 0 clamp(18px, 6vw, 74px);
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-paper-bright);
  color: var(--comic-ink);
  box-shadow: var(--comic-shadow);
}

.archive-empty svg {
  color: var(--comic-lime);
  font-size: 34px;
}

.archive-empty span {
  color: rgba(255, 255, 255, 0.58);
  font-size: 13px;
}

@media (max-width: 720px) {
  .archive-scene {
    gap: 18px;
    padding-top: max(70px, env(safe-area-inset-top));
  }

  .archive-timeline {
    grid-auto-columns: minmax(176px, 72vw);
    gap: 14px;
    padding-inline: 18px;
  }

  .archive-card {
    grid-template-rows: minmax(300px, 58dvh) auto;
    border-radius: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .archive-card {
    transition-duration: 1ms;
  }
}
</style>

<style scoped>
.archive-view { background: #f7fbff; color: #22315d; font-family: "Nunito", "Avenir Next", sans-serif; }
.archive-ambient { background: radial-gradient(circle at 20% 24%, #ffd977 0 7%, transparent 30%), radial-gradient(circle at 76% 70%, #65dfd2 0 9%, transparent 34%), #dff4ff; opacity: 1; }
.archive-control, .archive-owner, .archive-title > svg, .archive-card, .archive-empty { border: 0 !important; box-shadow: none !important; }
.archive-owner { border-radius: 999px !important; background: #fff !important; }.archive-owner__avatar, .archive-owner__avatar img { border: 0 !important; border-radius: 50% !important; }
.archive-title > svg { border-radius: 50% !important; background: #ff819b !important; }.archive-title h1 { font-family: inherit !important; font-weight: 900 !important; }.archive-title p { color: #546486 !important; }
.archive-periods { display: flex; align-items: center; justify-content: center; gap: 10px; position: relative; z-index: 2; }
.archive-periods button, .archive-periods select { border: 0; border-radius: 999px; min-height: 40px; padding: 0 14px; background: #fff; color: #2d3768; font: inherit; font-weight: 800; cursor: pointer; }
.archive-periods button:disabled { opacity: .45; cursor: default; }
.archive-orbit { position: relative; width: min(660px, 94vw); height: min(660px, 64dvh); min-height: 430px; margin: 0 auto; display: block; overflow: hidden; padding: 0; touch-action: pan-y; scroll-snap-type: none; }
.archive-orbit::before { content: ""; position: absolute; inset: 50% auto auto 50%; width: min(480px, 78vw); aspect-ratio: 1; border-radius: 50%; background: #a28aff; transform: translate(-50%, -50%); }
.archive-orbit__core { position: absolute; z-index: 2; inset: 50% auto auto 50%; width: 170px; aspect-ratio: 1; display: grid; place-content: center; gap: 7px; border-radius: 50%; background: #ffe36d; color: #3a3776; text-align: center; transform: translate(-50%, -50%); pointer-events: none; }
.archive-orbit__core strong { padding: 0 18px; font-size: 17px; text-transform: capitalize; }.archive-orbit__core span { font-size: 11px; }
.archive-orbit .archive-card { position: absolute; top: 50%; left: 50%; width: 112px; min-width: 0; height: 112px; display: block; padding: 5px; border-radius: 50%; background: #fff; transition: transform 220ms ease; }
.archive-orbit .archive-card__media { width: 100%; height: 100%; min-height: 0; border: 0; border-radius: 50%; background: #ff9f81; box-shadow: none; }.archive-orbit .archive-card__media img, .archive-orbit .archive-card__media video { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; }
.archive-orbit .archive-card__copy { display: none; }
@media (prefers-reduced-motion: reduce) { .archive-orbit .archive-card { transition: none; } }
@media (max-width: 600px) { .archive-orbit { min-height: 390px; height: 54dvh; }.archive-orbit .archive-card { width: 90px; height: 90px; }.archive-orbit::before { width: 330px; }.archive-periods select { max-width: 220px; } }
</style>
