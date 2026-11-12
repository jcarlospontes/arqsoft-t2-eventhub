package br.uece.eventhub.registration;

/** Erro de negocio/aplicacao convertido em resposta HTTP. */
final class ApiException extends RuntimeException {
    final int status;
    final String code;

    ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
