package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.Bm25TokenDictionaryEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface Bm25TokenDictionaryMapper {
    List<Bm25TokenDictionaryEntry> upsertTokens(@Param("tokens") List<String> tokens);
}
