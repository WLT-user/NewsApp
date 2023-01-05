package com.demo.viewnews;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.viewnews.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;

import org.litepal.LitePal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

//每个tab下的碎片Fragment
public class NewsFragment extends Fragment {
    //新闻列表
    private ListView newsListView;
    //下拉刷新
    private SwipeRefreshLayout swipeRefreshLayout;
    //新闻子项
    private List<NewsBean.ResultBean.DataBean> contentItems = new ArrayList<>();

    private static final int UPNEWS_INSERT = 0;

    //用来保存当前tab的名字
    private String currentTabName = "top";

    //分页查询参数，每页显示20条新闻数据
    private int pageNo = 0, pageSize = 20;

    //每一个Fragment页面都有一个浮动按钮，用于快速回到顶部
    private FloatingActionButton fab;

    @SuppressLint("HandlerLeak")
    private Handler newsHandler = new Handler() {

        //主线程
        //msg==UPNEWS_INSERT时，网络请求数据更新界面并存储本地
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UPNEWS_INSERT:
                    //从服务器来获取NewsBean数据
                    contentItems = ((NewsBean) msg.obj).getResult().getData();
                    //构造一个适配器来填充新闻列表
                    TabAdapter adapter = new TabAdapter(getActivity(), contentItems);
                    newsListView.setAdapter(adapter);
                    //当adapter中的数据被更改后必须马上调用notifyDataSetChanged予以更新。
                    adapter.notifyDataSetChanged();
                    //从服务器获取的数据缓存到本地数据库，注意去重插入的数据
                    NewsInfoBean newsInfo;
                    for (int i = 0, len = contentItems.size(); i < len; ++i) {
                        newsInfo = new NewsInfoBean(contentItems.get(i));
                        //将数据缓存到本地数据库
                        newsInfo.save();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    //每次创建，绘制该Fragment的View组件时回调，将显示的View返回系统创建Fragment的时候调用此函数，一般在此函数内实例化一些变量
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        //加载新闻列表
        View view = inflater.inflate(R.layout.news_list, container, false);
        //获取每个实例之后，返回当前视图
        newsListView = (ListView) view.findViewById(R.id.newsListView);
        fab = (FloatingActionButton) view.findViewById(R.id.fab);
        swipeRefreshLayout = (SwipeRefreshLayout) view.findViewById(R.id.swipe_refresh);
        return view;
    }

    //与Activity相关的UI交互操作
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        onAttach(getContext());
        Log.d("上下文：", "Context: " + getContext());
        Log.d("NewsFragment", "Activity: " + getActivity());

        Bundle bundle = getArguments();
        final String category = bundle.getString("name", "top");
        currentTabName = category;
        Log.d("点击tab小标题为：", "onActivityCreated: " + category);
        //实现置顶功能
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //滚动到第一个可见的item位置，下标为0
                newsListView.smoothScrollToPosition(0);
            }
        });

        //实现下拉刷新的功能
        swipeRefreshLayout.setColorSchemeResources(R.color.colorRed);
        //实现下拉刷新的监听器
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                threadLoaderData(category);
            }
        });
        //异步加载数据，传入条目
        getDataFromNet(category);

        // 轻度按监听新闻列表子项
        newsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.d("子项的数据为", "onItemClick: " + contentItems.get(position));
                //获取点击条目的路径，传值显示WebView页面
                String url = contentItems.get(position).getUrl();
                Log.d("当前新闻子项的连接是：", "onItemClick: " + url);
                String uniquekey = contentItems.get(position).getUniquekey();
                String newsTitle = contentItems.get(position).getTitle();
                Intent intent = new Intent(getActivity(), WebActivity.class);
                intent.putExtra("pageUrl", url);
                intent.putExtra("uniquekey", uniquekey);
                intent.putExtra("news_title", newsTitle);
                startActivity(intent);
            }
        });
    }

    private void threadLoaderData(final String category) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    //沉睡1s，本地刷新很快，以防看不到刷新效果
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                //若快速点击tab，则会出现getActivity()为空的情况，但是第一次加载肯定不会出错，所以将要拦截，以防app崩溃
                if (getActivity() == null)
                    return;
                //runOnUiThread必须是在主线程中调用，getActivity()获取主线程所在的活动，切换子线程到主线程
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //重新生成数据，传入tab条目
                        loaderRefreshData(category);
                        //表示刷新事件结束，并隐藏刷新进度条
                        swipeRefreshLayout.setRefreshing(false);
                    }
                });
            }
        }).start();
    }

    //加载数据，实现从本地数据库中读取数据刷新到newsListView的适配器中
    private void loaderRefreshData(final String category) {
        //top，shehui，guonei，guoji，yule，tiyu，junshi，keji，caijing，shishang
        String categoryName = "头条";
        if (category.equals("top")) {
            categoryName = "头条";
        } else if (category.equals("shehui")) {
            categoryName = "社会";
        } else if (category.equals("guonei")) {
            categoryName = "国内";
        } else if (category.equals("guoji")) {
            categoryName = "国际";
        } else if (category.equals("yule")) {
            categoryName = "娱乐";
        } else if (category.equals("tiyu")) {
            categoryName = "体育";
        } else if (category.equals("junshi")) {
            categoryName = "军事";
        } else if (category.equals("keji")) {
            categoryName = "科技";
        } else if (category.equals("caijing")) {
            categoryName = "财经";
        } else if (category.equals("shishang")) {
            categoryName = "时尚";
        }
        //页数加1
        ++pageNo;
        List<NewsBean.ResultBean.DataBean> dataBeanList = new ArrayList<>();
        NewsBean.ResultBean.DataBean bean = null;
        int offsetV = (pageNo - 1) * pageSize;
        Log.d("?-?-pageNo", "页数为: " + pageNo);
        Log.d("?-?-offsetV", "偏移量为: " + offsetV);
        Log.d("?-?-offsetV", "以下开始查询");
        List<NewsInfoBean> beanList = LitePal.where("category = ?", categoryName).limit(pageSize).offset(offsetV).find(NewsInfoBean.class);
        Log.d("?-?-TAG", "查询的数量为：" + beanList.size());
        //若查询的结果为0，则重新定位页数为1
        if (beanList.size() == 0) {
            pageNo = 1;
            offsetV = (pageNo - 1) * pageSize;
            beanList = LitePal.where("category = ?", categoryName).limit(pageSize).offset(offsetV).find(NewsInfoBean.class);
            Log.d("分页查询", "loaderRefreshData: 已经超过最大页数，归零并重新查询！");
        }
        Log.d("刷新查到的数据大小", "run: " + beanList.size());
        for (int i = 0, len = beanList.size(); i < len; ++i) {
            bean = new NewsBean.ResultBean.DataBean();
            bean.setDataBean(beanList.get(i));
            dataBeanList.add(bean);
            Log.d("刷新id：", "run: " + beanList.get(i));
        }
        contentItems = dataBeanList;
        //将dataBeanList赋值给全局的contentItems，否则点击新闻子项会出错,并且contentItems之前要清空，不然起不到更新视图的作用
        TabAdapter adapter = new TabAdapter(getActivity(), contentItems);
        newsListView.setAdapter(adapter);
        //当adapter中的数据被更改后必须马上调用notifyDataSetChanged予以更新。
        adapter.notifyDataSetChanged();
    }

    //异步消息处理机制
    private void getDataFromNet(final String data) {
        @SuppressLint("StaticFieldLeak")
        AsyncTask<Void, Void, String> task = new AsyncTask<Void, Void, String>() {
            //子线程
            @Override
            protected String doInBackground(Void... params) {
                String path = "http://v.juhe.cn/toutiao/index?type=" + data + "&key=dadc7f4b977bcb86163d86d6f7cb1537";
                URL url = null;
                try {
                    url = new URL(path);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    //设置请求方式
                    connection.setRequestMethod("GET");
                    //设置读取超时的毫秒数
                    connection.setReadTimeout(5000);
                    //设置连接超时时间
                    connection.setConnectTimeout(5000);
                    //获取状态码
                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) { // 200
                        //获取服务器返回的输入流
                        InputStream inputStream = connection.getInputStream();
                        String json = streamToString(inputStream, "utf-8");
                        //返回任务的执行结果
                        return json;
                    } else {
                        //返回的状态码不是200
                        System.out.println(responseCode);
                        return 404 + data;
                    }
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (ProtocolException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return 404 + data;
            }

            //当给后台任务执行完毕并通过return语句返回时，此方法将被调用，返回来的数据可以进行一些UI操作，并将处理的参数传入
            protected void onPostExecute(final String result) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        //查看状态码是否为200，若不是（开子线程），然后从本地加载相应的数据
                        NewsBean newsBean = null;
                        //不包括endIndex
                        Log.d("后台处理的数据为：", "run: " + result);
                        if (!result.substring(0, 3).equals("404")) {
                            newsBean = new Gson().fromJson(result, NewsBean.class);
                            System.out.println(newsBean.getError_code());
                            if ("0".equals("" + newsBean.getError_code())) {
                                //obtainmessage()方法是从消息池中拿来一个msg，不需要另开辟空间new，new需要重新申请，效率低，obtianmessage可以循环利用；
                                Message msg = newsHandler.obtainMessage();
                                msg.what = UPNEWS_INSERT;
                                msg.obj = newsBean;
                                //发送一个通知来填充数据，因为安卓不允许在子线程中进行UI操作
                                newsHandler.sendMessage(msg);
                            } else {
                                //实现从数据库加载数据
                                Log.d("超过请求次数或者其他原因", "run: 现在从本地数据库中获取");
                                Log.d("当前tab名字为：", "run: " + currentTabName);
                                threadLoaderData(currentTabName);
                            }
                        } else {
                            threadLoaderData(result.substring(3));
                        }
                    }
                }).start();
            }

            //当后台任务中调用了publishProgress(Progress...)方法后，onProgressUpdate方法很快被执行
            @Override
            protected void onProgressUpdate(Void... values) {
                super.onProgressUpdate(values);
            }
        };
        //启动异步加载任务
        task.execute();
    }

    //输入流转化成字符串
    private String streamToString(InputStream inputStream, String charset) {
        try {
            //创建一个使用命名字符集的InputStreamReader。 InputStreamReader(InputStream in, String charsetName)
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, charset);
            //构造一个字符输入流对象
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String s = null;
            StringBuilder builder = new StringBuilder();
            while ((s = bufferedReader.readLine()) != null) {
                builder.append(s);
            }
            //关闭流
            bufferedReader.close();
            inputStreamReader.close();
            return builder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}