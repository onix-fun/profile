FROM node:22-alpine AS build
WORKDIR /app
COPY profile/frontend/package*.json ./
RUN npm install
COPY profile/frontend ./
RUN npm run build

FROM nginx:1.27-alpine
COPY profile/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY profile/frontend/docker-entrypoint.d/40-profile-runtime-config.sh /docker-entrypoint.d/40-profile-runtime-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
