# Instrucciones del backend MIRIV

@../QWEN.md

Las rutas docs/seguimiento-enologico y .qwen/skills de las instrucciones importadas se refieren a la raíz MIRIV, un nivel por encima de este repositorio. Para consultar el índice desde aquí, abre [INDICE.md](../docs/seguimiento-enologico/INDICE.md).

Comprueba pom.xml, configuración efectiva, migraciones y pruebas antes de trabajar. La revisión inicial encontró Java 25, Spring Boot, Maven, JPA, PostgreSQL y Flyway; esto es una observación del repositorio, no un requisito extraído de los DOCX. Revalida versiones en el manifiesto.

Mantén reglas de permisos, trazabilidad, balance, idempotencia y concurrencia en el servidor. No reescribas migraciones ya aplicadas como atajo para un cambio de esquema. No ejecutes pruebas de integración contra datos operativos: revisa su perfil y conexión.

Las skills comunes están en ../.qwen/skills. Si no se descubren desde esta raíz Git, lee el archivo SKILL.md correspondiente o inicia Qwen Code desde MIRIV.

