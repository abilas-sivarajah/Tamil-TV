package com.tvclient.app.data.remote

import com.tvclient.app.data.model.CategoriesResponse
import com.tvclient.app.data.model.MovieListResponse
import com.tvclient.app.data.model.StreamResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Vertrag zum Backend. Siehe API-SCHEMA.md fuer die exakten JSON-Formate.
 */
interface ApiService {

    @GET("api/categories")
    suspend fun categories(): CategoriesResponse

    @GET("api/movies")
    suspend fun movies(
        @Query("category") category: String,
        @Query("page") page: Int = 1
    ): MovieListResponse

    @GET("api/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 1
    ): MovieListResponse

    @GET("api/stream")
    suspend fun stream(
        @Query("id") id: String
    ): StreamResponse
}
