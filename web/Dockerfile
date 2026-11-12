# Build a partir da raiz do repositorio (ver docker-compose.yml), para reaproveitar docs/
FROM nginx:1.27-alpine
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
COPY web/index.html /usr/share/nginx/html/index.html
COPY docs/openapi-registration.yaml /usr/share/nginx/html/openapi-registration.yaml
EXPOSE 80
