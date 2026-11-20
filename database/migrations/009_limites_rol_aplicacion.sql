-- 009: Límites de seguridad y estabilidad para el rol de la aplicación
-- Evitan que una consulta descontrolada, un bloqueo o una conexión olvidada degraden el sistema en producción.

alter role app_backend set statement_timeout = '30s';                       -- ninguna consulta corre más de 30 s
alter role app_backend set lock_timeout = '10s';                            -- no esperar bloqueos de fila indefinidamente
alter role app_backend set idle_in_transaction_session_timeout = '60s';     -- cierra transacciones abandonadas
alter role app_backend connection limit 25;                                 -- tope de conexiones simultáneas del rol
