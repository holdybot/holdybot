FROM openjdk:8-alpine

COPY target/uberjar/parky.jar /parky/app.jar

EXPOSE 3000

CMD ["java", "-jar", "/parky/app.jar"]
