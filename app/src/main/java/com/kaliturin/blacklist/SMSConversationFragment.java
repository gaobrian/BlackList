package com.kaliturin.blacklist;


import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.Toast;


/**
 * Fragment for showing one SMS conversation
 */
public class SMSConversationFragment extends Fragment implements FragmentArguments {
    private static final int END_OF_LIST = -1;
    private InternalEventBroadcast internalEventBroadcast = null;
    private SMSConversationCursorAdapter cursorAdapter = null;
    private ListView listView = null;

    public SMSConversationFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_sms_conversation, container, false);
    }

    @Override
    public void onViewCreated(final View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // notify user if permission isn't granted
        Permissions.notifyIfNotGranted(getContext(), Permissions.READ_SMS);

        // init internal broadcast event receiver
        internalEventBroadcast = new InternalEventBroadcast() {
            @Override
            public void onSMSInboxWrite(@NonNull String number) {
                Bundle arguments = getArguments();
                if(arguments != null) {
                    String contactNumber = arguments.getString(CONTACT_NUMBER);
                    if(contactNumber != null && contactNumber.equals(number)) {
                        // reload sms messages in the list
                        loadListViewItems(END_OF_LIST, 1);
                    }
                }
            }
        };
        internalEventBroadcast.register(getContext());

        // cursor adapter
        cursorAdapter = new SMSConversationCursorAdapter(getContext());
        cursorAdapter.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View row) {
                final ContactsAccessHelper.SMSMessage sms = cursorAdapter.getSMSMessage(row);
                if(sms == null) {
                    return true;
                }
                // create menu dialog
                MenuDialogBuilder dialog = new MenuDialogBuilder(getActivity());
                // 'delete message'
                dialog.addItem(R.string.Delete_message, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
                        db.deleteSMSMessageById(getContext(), sms.id);
                        // reload sms messages in the list
                        int listPosition = listView.getFirstVisiblePosition();
                        loadListViewItems(listPosition, 0);
                    }
                });
                // 'copy text' to clipboard
                dialog.addItem(R.string.Copy_message, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if(Utils.copyTextToClipboard(getContext(), sms.body)) {
                            Toast.makeText(getContext(), R.string.Copied_to_clipboard,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                // 'forward message'
                dialog.addItem(R.string.Forward_message, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSMSSendActivity("", "", sms.body);
                    }
                });
                dialog.show();

                return true;
            }
        });

        // add cursor listener to the list
        listView = (ListView) view.findViewById(R.id.rows_list);
        listView.setAdapter(cursorAdapter);

        // load sms messages of the conversation to the list view
        Bundle arguments = getArguments();
        if(arguments != null) {
            int threadId = arguments.getInt(THREAD_ID);
            int unreadCount = arguments.getInt(UNREAD_COUNT);
            // load sms messages of the conversation to the list view
            loadListViewItems(threadId, unreadCount, END_OF_LIST);
        }
    }

    @Override
    public void onDestroyView() {
        getLoaderManager().destroyLoader(0);
        internalEventBroadcast.unregister(getContext());
        super.onDestroyView();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.main, menu);

        MenuItem writeSMS = menu.findItem(R.id.write_message);
        Utils.setMenuIconTint(getContext(), writeSMS, R.color.colorAccent);
        writeSMS.setVisible(true);

        writeSMS.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                // get showed first sms
                View row = listView.getChildAt(0);
                ContactsAccessHelper.SMSMessage sms = cursorAdapter.getSMSMessage(row);
                if(sms != null) {
                    openSMSSendActivity(sms.person, sms.number, "");
                }
                return true;
            }
        });

        super.onCreateOptionsMenu(menu, inflater);
    }

//----------------------------------------------------

    // Opens activity with SMS-sending fragment
    void openSMSSendActivity(String person, String number, String body) {
        // put arguments for the SMS sending fragment
        Bundle arguments = new Bundle();
        arguments.putString(CONTACT_NAME, person);
        arguments.putString(CONTACT_NUMBER, number);
        arguments.putString(SMS_MESSAGE_BODY, body);
        // open activity with the fragment
        CustomFragmentActivity.show(getContext(),
                getString(R.string.new_message),
                SMSSendFragment.class, arguments);
    }

    // Loads SMS conversation to the list view and scrolls to passed position
    private void loadListViewItems(int listPosition, int unreadCount) {
        Bundle arguments = getArguments();
        if(arguments != null) {
            int threadId = arguments.getInt(THREAD_ID);
            // load sms messages of the conversation to the list view
            loadListViewItems(threadId, unreadCount, listPosition);
        }
    }

    // Loads SMS conversation to the list view
    private void loadListViewItems(int threadId, int unreadCount, int listPosition) {
        int loaderId = 0;
        ConversationLoaderCallbacks callbacks =
            new ConversationLoaderCallbacks(getContext(),
                threadId, unreadCount, listView, listPosition, cursorAdapter);

        LoaderManager manager = getLoaderManager();
        if (manager.getLoader(loaderId) == null) {
            // init and run the items loader
            manager.initLoader(loaderId, null, callbacks);
        } else {
            // restart loader
            manager.restartLoader(loaderId, null, callbacks);
        }
    }

//----------------------------------------------------

    // SMS conversation loader
    private static class ConversationLoader extends CursorLoader {
        private int threadId;

        ConversationLoader(Context context, int threadId) {
            super(context);
            this.threadId = threadId;
        }

        @Override
        public Cursor loadInBackground() {
            ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
            // get SMS records by thread id
            return db.getSMSMessagesByThreadId(getContext(), threadId, false, 0);
        }
    }

    // SMS conversation loader callbacks
    private static class ConversationLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        private Context context;
        private int threadId;
        private int unreadCount;
        private ListView listView;
        private int listPosition;
        private SMSConversationCursorAdapter cursorAdapter;

        ConversationLoaderCallbacks(Context context, int threadId, int unreadCount, ListView listView,
                                    int listPosition, SMSConversationCursorAdapter cursorAdapter) {
            this.context = context;
            this.threadId = threadId;
            this.unreadCount = unreadCount;
            this.listView = listView;
            this.listPosition = listPosition;
            this.cursorAdapter = cursorAdapter;
        }

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new ConversationLoader(context, threadId);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
            // apply loaded data to cursor adapter
            cursorAdapter.changeCursor(cursor);

            // scroll list to bottom
            listView.post(new Runnable() {
                @Override
                public void run() {
                    int pos = (listPosition == END_OF_LIST ?
                            cursorAdapter.getCount() - 1 : listPosition);
                    listView.setSelection(pos);
                    listView.setVisibility(View.VISIBLE);
                }
            });

            // is there unread sms in the thread
            if(unreadCount > 0) {
                // mark sms ot the thread are read
                new SMSReadMarker(context).execute(threadId);
            }
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            cursorAdapter.changeCursor(null);
        }
    }

    // Async task - marks SMS of the thread are read
    static class SMSReadMarker extends AsyncTask<Integer, Void, Void> {
        private Context context;

        SMSReadMarker(Context context) {
            this.context = context;
        }

        @Override
        protected Void doInBackground(Integer... params) {
            // mark all messages from thread as read
            int threadId = params[0];
            ContactsAccessHelper db = ContactsAccessHelper.getInstance(context);
            if(db.setSMSMessagesReadByThreadId(context, threadId)) {
                // send broadcast event that SMS thread was read
                InternalEventBroadcast.sendSMSInboxRead(context, threadId);
            }
            return null;
        }
    }
}
