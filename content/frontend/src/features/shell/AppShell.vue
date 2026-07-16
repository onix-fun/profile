<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { Building2, Film, Home, Menu, PlusCircle, Search, Settings, User } from "lucide-vue-next";
import { ContentService } from "@/api/contentService";
import type { AccountUser, CurrentActor } from "@/api/types";
import { accountSettingsUrl } from "@/api/navigation";

const router = useRouter();
const route = useRoute();
const actor = ref<CurrentActor | null>(null);
const menuOpen = ref(false);
const createOpen = ref(false);

const activeOwner = computed<AccountUser | null>(() => actor.value?.activeOwner || null);
const isOrg = computed(() => activeOwner.value?.ownerType === "ORGANIZATION");
const initials = computed(() => {
  const source = activeOwner.value?.displayName || activeOwner.value?.username || "U";
  return source.slice(0, 1).toUpperCase();
});
const ownerName = computed(() => activeOwner.value?.displayName || activeOwner.value?.username || "Account");
const profilePath = computed(() => {
  const owner = activeOwner.value;
  if (!owner) return "/u/me";
  const prefix = owner.ownerType === "ORGANIZATION" ? "o" : "u";
  return `/${prefix}/${encodeURIComponent(owner.username)}`;
});
const controlsHidden = computed(() => (
  route.name === "CreatePost"
  || route.name === "CreateStory"
  || route.name === "StoryArchive"
  || route.name === "StoryViewer"
));
const headerVisible = computed(() => (
  !controlsHidden.value
  && route.path !== "/"
  && route.name !== "Profile"
  && route.name !== "SocialCanvas"
));
const settingsHref = computed(() => accountSettingsUrl(window.location.href));

onMounted(async () => {
  try {
    actor.value = await ContentService.currentActor();
  } catch {
    actor.value = null;
  }
});

function closeMenu() {
  menuOpen.value = false;
  createOpen.value = false;
}

function openCreatePost() {
  closeMenu();
  void router.push("/p/new");
}

function openDrafts() {
  closeMenu();
  void router.push("/p/new?drafts=1");
}

function openCreateStory() {
  closeMenu();
  void router.push("/story/new");
}
</script>

<template>
  <div class="app-shell" @keydown.esc="closeMenu">
    <header v-if="headerVisible" class="app-header" aria-label="Основная навигация">
      <RouterLink class="brand-mark" to="/" aria-label="Открыть ленту"><span class="brand-word">Onix</span></RouterLink>
    </header>

    <Transition name="menu-fade">
      <aside v-if="menuOpen && !controlsHidden" class="account-menu" aria-label="Account menu">
        <div class="account-menu__identity">
          <div class="account-menu__avatar">
            <img v-if="activeOwner?.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
            <Building2 v-else-if="isOrg" :size="22" />
            <span v-else>{{ initials }}</span>
          </div>
          <div>
            <strong>{{ ownerName }}</strong>
            <span>@{{ activeOwner?.username }}</span>
          </div>
        </div>

        <nav class="account-menu__nav">
          <RouterLink to="/" @click="closeMenu"><Home :size="18" />Feed</RouterLink>
          <RouterLink :to="profilePath" @click="closeMenu"><Building2 v-if="isOrg" :size="18" /><User v-else :size="18" />Profile</RouterLink>
          <RouterLink to="/search" @click="closeMenu"><Search :size="18" />Search</RouterLink>
          <button type="button" @click="openCreatePost"><PlusCircle :size="18" />Create post</button>
          <button type="button" @click="openCreateStory"><Film :size="18" />Create story</button>
          <a :href="settingsHref" @click="closeMenu"><Settings :size="18" />Settings</a>
        </nav>
      </aside>
    </Transition>

    <Transition name="menu-fade">
      <aside v-if="createOpen && !controlsHidden" class="create-menu" aria-label="Создание">
        <button type="button" @click="openCreatePost"><PlusCircle :size="18" />Новый пост</button>
        <button type="button" @click="openDrafts"><i class="pi pi-file-edit"></i>Черновики</button>
        <button type="button" @click="openCreateStory"><Film :size="18" />Новая история</button>
      </aside>
    </Transition>

    <nav v-if="!controlsHidden" class="aurora-dock" aria-label="Главная навигация">
      <RouterLink to="/" aria-label="Лента"><Home :size="20" /></RouterLink>
      <RouterLink to="/search" aria-label="Поиск"><Search :size="20" /></RouterLink>
      <button type="button" class="aurora-dock__create" aria-label="Создать" :aria-expanded="createOpen" @click="createOpen = !createOpen; menuOpen = false"><PlusCircle :size="25" /></button>
      <RouterLink :to="profilePath" aria-label="Профиль"><Building2 v-if="isOrg" :size="20" /><User v-else :size="20" /></RouterLink>
      <button
        class="aurora-dock__profile avatar-menu-button"
        :class="{ 'avatar-menu-button--org': isOrg }"
        type="button"
        aria-label="Меню аккаунта"
        :aria-expanded="menuOpen"
        @click="menuOpen = !menuOpen; createOpen = false"
      >
        <img v-if="activeOwner?.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
        <Building2 v-else-if="isOrg" :size="19" />
        <span v-else>{{ initials }}</span>
        <Menu :size="12" />
      </button>
    </nav>

    <main class="app-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100dvh;
  background:
    radial-gradient(circle at 18% 22%, rgba(0, 229, 255, 0.16), transparent 28%),
    radial-gradient(circle at 82% 12%, rgba(255, 79, 123, 0.14), transparent 26%),
    linear-gradient(180deg, var(--comic-paper) 0%, #f5f0d6 100%);
}

.app-header {
  position: fixed;
  z-index: 80;
  top: 0;
  left: 0;
  right: 0;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 18px clamp(18px, 3vw, 42px);
  pointer-events: none;
}

.brand-mark,
.avatar-menu-button {
  pointer-events: auto;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--comic-ink);
  text-decoration: none;
  font-family: var(--display-font);
  font-weight: 400;
  letter-spacing: 0;
  text-transform: uppercase;
  text-shadow: var(--comic-text-shadow);
}

.brand-word {
  font-size: 22px;
}

.avatar-menu-button {
  position: fixed;
  z-index: 86;
  right: clamp(18px, 3vw, 42px);
  bottom: max(22px, env(safe-area-inset-bottom));
  width: 56px;
  height: 50px;
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-yellow);
  color: var(--comic-ink);
  box-shadow: var(--comic-shadow-small);
  cursor: pointer;
  overflow: visible;
  transform: rotate(-3deg);
  clip-path: polygon(0 0, 82% 0, 100% 50%, 82% 100%, 0 100%);
}

.avatar-menu-button img,
.avatar-menu-button > svg:not(.avatar-menu-button__menu),
.avatar-menu-button > span {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  border-radius: 4px;
  object-fit: cover;
  font-weight: 900;
}

.avatar-menu-button--org {
  background: var(--comic-cyan);
  box-shadow: var(--comic-shadow-small);
}

.avatar-menu-button__menu {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 22px;
  height: 22px;
  padding: 3px;
  display: grid;
  place-items: center;
  border: 3px solid var(--comic-ink);
  border-radius: 6px;
  background: var(--comic-coral);
  color: #ffffff;
}

.account-menu {
  position: fixed;
  z-index: 90;
  right: clamp(14px, 3vw, 34px);
  bottom: calc(max(22px, env(safe-area-inset-bottom)) + 64px);
  width: min(320px, calc(100vw - 28px));
  padding: 14px;
  border: var(--comic-line);
  border-radius: 8px;
  background: var(--comic-paper-bright);
  box-shadow: var(--comic-shadow);
  transform: rotate(1deg);
}

.account-menu__identity {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 8px 12px;
  border-bottom: 4px dashed rgba(5, 7, 11, 0.22);
}

.account-menu__avatar {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border: 3px solid var(--comic-ink);
  border-radius: 8px;
  background: var(--comic-lime);
  color: var(--comic-ink);
  font-weight: 900;
  overflow: hidden;
}

.account-menu__avatar img,
.account-menu__avatar svg {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  object-fit: cover;
}

.account-menu__identity strong,
.account-menu__identity span {
  display: block;
}

.account-menu__identity span {
  color: #46505a;
  font-size: 13px;
  font-weight: 700;
}

.account-menu__nav {
  display: grid;
  gap: 4px;
  padding-top: 10px;
}

.account-menu__nav a,
.account-menu__nav button {
  width: 100%;
  height: 42px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 6px;
  padding: 0 10px;
  background: transparent;
  color: var(--comic-ink);
  text-decoration: none;
  font: inherit;
  font-weight: 900;
  cursor: pointer;
}

.account-menu__nav a:hover,
.account-menu__nav button:hover {
  background: var(--comic-yellow);
  box-shadow: inset 0 0 0 3px var(--comic-ink);
}

.app-main {
  min-height: 100dvh;
}

.menu-fade-enter-active,
.menu-fade-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.menu-fade-enter-from,
.menu-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>

<style scoped>
.app-shell { background:#f6fbff !important; font-family:"Nunito", "Avenir Next", "Roboto", sans-serif; }
.app-header { height:64px !important; padding:15px 24px !important; }
.brand-mark { color:#17233c !important; font-family:inherit !important; font-weight:900 !important; text-shadow:none !important; text-transform:none !important; }
.brand-word { font-size:23px !important; }
.aurora-dock { position:fixed; z-index:86; right:24px; bottom:24px; display:flex; align-items:center; gap:7px; padding:8px; border-radius:999px; background:#ffffff; box-shadow:none; }
.aurora-dock a, .aurora-dock button { width:42px; height:42px; display:grid; place-items:center; border:0; border-radius:50%; background:#eef7ff; color:#17233c; cursor:pointer; text-decoration:none; transition:transform 150ms ease, background 150ms ease; }
.aurora-dock a.router-link-active { background:#9eea38; }
.aurora-dock a:hover, .aurora-dock button:hover, .aurora-dock a:focus-visible, .aurora-dock button:focus-visible { transform:translateY(-3px); background:#c7ecff; }
.aurora-dock__create { width:54px !important; height:54px !important; margin:-15px 1px; background:#ff5ab1 !important; color:#17233c !important; }
.aurora-dock__profile { position:relative; overflow:hidden; background:#6856e9 !important; color:#fff !important; font-weight:900; }
.aurora-dock__profile img { position:absolute; inset:0; width:100%; height:100%; object-fit:cover; }
.aurora-dock__profile svg:last-child { position:absolute; right:3px; bottom:3px; padding:2px; border-radius:50%; background:#17233c; color:#fff; }
.account-menu, .create-menu { position:fixed; z-index:90; right:24px; bottom:91px; width:min(310px, calc(100vw - 32px)); border:0 !important; border-radius:28px !important; padding:14px !important; background:#fff !important; box-shadow:none !important; transform:none !important; }
.create-menu { display:grid; gap:6px; }
.create-menu button { height:46px; display:flex; align-items:center; gap:10px; border:0; border-radius:16px; padding:0 13px; background:#edf7ff; color:#17233c; font:inherit; font-weight:800; cursor:pointer; text-align:left; }
.create-menu button:nth-child(1) { background:#ffcb5c; }.create-menu button:nth-child(2) { background:#c7ecff; }.create-menu button:nth-child(3) { background:#9eea38; }
.account-menu__identity { border-bottom:0 !important; }.account-menu__avatar { border:0 !important; border-radius:50% !important; background:#9eea38 !important; }.account-menu__nav a, .account-menu__nav button { border-radius:15px !important; }.account-menu__nav a:hover, .account-menu__nav button:hover { background:#c7ecff !important; box-shadow:none !important; }
@media (max-width:720px) { .app-header { display:none !important; }.aurora-dock { right:50%; bottom:max(14px, env(safe-area-inset-bottom)); transform:translateX(50%); }.account-menu, .create-menu { right:50%; bottom:86px; transform:translateX(50%) !important; } }
</style>
