-- Solo la rotación llena esta columna. La detección de reutilización mira solo los tokens reemplazados:
-- uno revocado por logout o por un cierre masivo de sesiones se rechaza sin provocar otra cascada.
ALTER TABLE refresh_tokens ADD COLUMN reemplazado_en timestamptz;
