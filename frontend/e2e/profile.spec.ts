import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const relationship = {
  isFollowing: false,
  isFollowedBy: false,
  isFriend: false,
  isBlocked: false,
  hasPendingRequest: false,
};

test.beforeEach(async ({ page }) => {
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith("/api/")) {
      await route.continue();
      return;
    }
    if (path.endsWith("/session/me")) {
      await route.fulfill({ json: { user: { id: "user-1", username: "demo" } } });
      return;
    }
    if (path.endsWith("/session/actor")) {
      const owner = { id: "user-1", ownerType: "USER", username: "demo", displayName: "Demo User" };
      await route.fulfill({ json: { actor: { user: { id: "user-1", username: "demo" }, activeOwner: owner } } });
      return;
    }
    if (path.endsWith("/profiles/demo")) {
      await route.fulfill({
        json: {
          status: "OK",
          profile: {
            id: "user-1",
            ownerType: "USER",
            username: "demo",
            displayName: "Demo User",
            bio: "Onix profile",
            socialLinks: [],
            followersCount: 0,
            followingCount: 0,
            isPrivate: false,
            relationship,
          },
          content: { posts: [], stories: [], comments: [], collections: [] },
          relationship,
          navigation: [],
          nodes: [],
          edges: [],
          permissions: { owner: true, canFollow: false },
          viewport: { x: 0, y: 0, zoom: 1 },
        },
      });
      return;
    }
    await route.fulfill({ status: 404, json: {} });
  });
});

test("successful profile never renders the fallback error screen", async ({ page }) => {
  await page.goto("/u/demo");
  await expect(page.locator(".canvas-shell")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Unable to load profile" })).toHaveCount(0);
  await expect(page.locator(".canvas-viewport")).toHaveCSS("height", `${await page.evaluate(() => window.innerHeight)}px`);
  const accessibility = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
  expect(accessibility.violations).toEqual([]);
});

test("profile controls meet the minimum touch target", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/u/demo");
  const button = page.locator(".onix-mobile-nav-trigger");
  await expect(button).toBeVisible();
  await expect(button).toHaveCount(1);
  const box = await button.boundingBox();
  expect(box?.width).toBeGreaterThanOrEqual(44);
  expect(box?.height).toBeGreaterThanOrEqual(44);
});
