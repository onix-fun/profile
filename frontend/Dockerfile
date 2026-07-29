FROM node:22-alpine AS build
WORKDIR /app
COPY onix-design-system/package*.json /onix-design-system/
RUN npm --prefix /onix-design-system ci --ignore-scripts
COPY onix-design-system/scripts /onix-design-system/scripts
COPY onix-design-system/src /onix-design-system/src
COPY onix-design-system/tokens /onix-design-system/tokens
COPY onix-design-system/icons /onix-design-system/icons
COPY onix-design-system/illustrations /onix-design-system/illustrations
RUN npm --prefix /onix-design-system run build
COPY profile/frontend/package.json profile/frontend/package-lock.json ./
RUN npm install --package-lock=false --ignore-scripts
COPY profile/frontend ./
RUN npm run build --ignore-scripts

FROM nginx:1.27-alpine
COPY profile/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY profile/frontend/docker-entrypoint.d/40-profile-runtime-config.sh /docker-entrypoint.d/40-profile-runtime-config.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
