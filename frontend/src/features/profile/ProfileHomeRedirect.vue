<script setup lang="ts">
import { onMounted } from "vue";
import { useRouter } from "vue-router";
import { ContentService } from "@/api/contentService";

const router = useRouter();

onMounted(async () => {
  const actor = await ContentService.currentActor();
  const owner = actor.activeOwner;
  const prefix = owner.ownerType === "ORGANIZATION" ? "o" : "u";
  await router.replace(`/${prefix}/${encodeURIComponent(owner.username)}`);
});
</script>

<template>
  <main class="profile-home-redirect">
    <i class="pi pi-spinner pi-spin"></i>
  </main>
</template>

<style scoped>
.profile-home-redirect {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: #64748b;
}
</style>
