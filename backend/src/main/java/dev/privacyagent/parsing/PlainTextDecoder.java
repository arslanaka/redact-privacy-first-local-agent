package dev.privacyagent.parsing;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decodes text files: strict UTF-8 first, Windows-1252 as a fallback. Rejects binary content. */
final class PlainTextDecoder {

    private PlainTextDecoder() {}

    static boolean looksLikeText(byte[] content) {
        int limit = Math.min(content.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                return false; // NUL bytes: binary file with a text extension
            }
        }
        return true;
    }

    static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            text = new String(content, Charset.forName("windows-1252"));
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
