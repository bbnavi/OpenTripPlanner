FROM adoptopenjdk/openjdk11:alpine-slim
MAINTAINER MFDZ version: 0.1

RUN apk add --update curl bash ttf-dejavu && \
    rm -rf /var/cache/apk/*
VOLUME /opt/opentripplanner/graphs

ENV OTP_ROOT="/opt/opentripplanner"
ENV ROUTER_DATA_CONTAINER_URL="http://graph/"

WORKDIR ${OTP_ROOT}

ADD run.sh ${OTP_ROOT}/run.sh
ADD target/*-shaded.jar ${OTP_ROOT}/otp-shaded.jar

ENV PORT=8080
EXPOSE ${PORT}
ENV SECURE_PORT=8081
EXPOSE ${SECURE_PORT}
ENV ROUTER_NAME=default
ENV JAVA_OPTS="-Xms8G -Xmx8G"

ENTRYPOINT exec ./run.sh
