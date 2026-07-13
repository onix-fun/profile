FROM node:22-alpine AS build
WORKDIR /app
COPY design-system/package.json ./
COPY design-system/tokens ./tokens
COPY design-system/scripts ./scripts
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
