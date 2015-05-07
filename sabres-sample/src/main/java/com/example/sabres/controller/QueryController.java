/*
 * Copyright 2015 Tamir Shomer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.sabres.controller;

import android.util.Log;

import com.example.sabres.model.Director;
import com.example.sabres.model.Movie;

import java.util.List;

import bolts.Continuation;
import bolts.Task;

public class QueryController {
    private static final String TAG = QueryController.class.getSimpleName();

    public void queryFightClub(boolean includeDirector) {
        Movie.findWithTitleInBackground(FightClubController.TITLE).continueWith(new Continuation<List<Movie>, Void>() {
            @Override
            public Void then(Task<List<Movie>> task) throws Exception {
                if (task.isFaulted()) {
                    Log.e(TAG, "findWithTitleInBackground failed", task.getError());
                } else if (task.getResult().isEmpty()) {
                    Log.e(TAG, "Fight Club movie does not exist");
                } else {
                    Director director = task.getResult().get(0).getDirector();
                    try {
                        Log.i(TAG, String.format("Director name is %s", director.getName()));
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to get Director name", e);
                    }
                }

                return null;
            }
        }, Task.UI_THREAD_EXECUTOR);
    }
}